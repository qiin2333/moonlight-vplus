#include <stdlib.h>
#include <stdio.h>
#include <string.h>

#include <sys/types.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <linux/input.h>
#include <unistd.h>
#include <poll.h>
#include <errno.h>
#include <dirent.h>
#include <pthread.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

#include <arpa/inet.h>

#include <android/log.h>

#define EVDEV_MAX_EVENT_SIZE 24
#define EVDEV_PACKET_VERSION 1

#define EVDEV_DEVICE_MOUSE 0x01
#define EVDEV_DEVICE_KEYBOARD 0x02
#define EVDEV_DEVICE_TOUCHPAD 0x04

#define REL_X 0x00
#define REL_Y 0x01
#define KEY_Q 16
#define BTN_LEFT 0x110
#define BTN_GAMEPAD 0x130

struct EvdevWirePacket {
    int version;
    int deviceId;
    int deviceClass;
    int eventSize;
    int absXMin;
    int absXMax;
    int absYMin;
    int absYMax;
    int absXResolution;
    int absYResolution;
    char eventData[EVDEV_MAX_EVENT_SIZE];
};

struct DeviceEntry {
    struct DeviceEntry *next;
    pthread_t thread;
    int fd;
    int deviceId;
    int deviceClass;
    struct input_absinfo absX;
    struct input_absinfo absY;
    char devName[128];
};

static struct DeviceEntry *DeviceListHead;
static int grabbing = 1;
// When 0, hardware touchpads are left to Android's input stack (native mouse
// behavior) instead of being grabbed and streamed as a precision touchpad.
static int optimizeTouchpad = 1;
static int NextDeviceId = 1;
static pthread_mutex_t DeviceListLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t SocketSendLock = PTHREAD_MUTEX_INITIALIZER;
static int sock;

// This is a small executable that runs in a root shell. It reads input
// devices and writes the evdev output packets to a socket. This allows
// Moonlight to read input devices without having to muck with changing
// device permissions or modifying SELinux policy (which is prevented in
// Marshmallow anyway).

#define test_bit(bit, array)    (array[bit/8] & (1<<(bit%8)))

static int hasRelAxis(int fd, short axis) {
    unsigned char relBitmask[(REL_MAX + 8) / 8];

    memset(relBitmask, 0, sizeof(relBitmask));
    ioctl(fd, EVIOCGBIT(EV_REL, sizeof(relBitmask)), relBitmask);

    return test_bit(axis, relBitmask);
}

static int hasKey(int fd, short key) {
    unsigned char keyBitmask[(KEY_MAX + 8) / 8];

    memset(keyBitmask, 0, sizeof(keyBitmask));
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keyBitmask)), keyBitmask);

    return test_bit(key, keyBitmask);
}

static int hasAbsAxis(int fd, short axis) {
    unsigned char absBitmask[(ABS_MAX + 8) / 8];

    memset(absBitmask, 0, sizeof(absBitmask));
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absBitmask)), absBitmask);

    return test_bit(axis, absBitmask);
}

static int hasInputProp(int fd, short prop) {
    unsigned char propBitmask[(INPUT_PROP_MAX + 8) / 8];

    memset(propBitmask, 0, sizeof(propBitmask));
    ioctl(fd, EVIOCGPROP(sizeof(propBitmask)), propBitmask);

    return test_bit(prop, propBitmask);
}

static void outputEvdevData(struct DeviceEntry *device, char *data, int dataSize) {
    struct EvdevWirePacket packet;
    int packetSize = sizeof(packet);
    char packetBuffer[sizeof(packet) + sizeof(packetSize)];

    memset(&packet, 0, sizeof(packet));
    packet.version = EVDEV_PACKET_VERSION;
    packet.deviceId = device->deviceId;
    packet.deviceClass = device->deviceClass;
    packet.eventSize = dataSize;
    packet.absXMin = device->absX.minimum;
    packet.absXMax = device->absX.maximum;
    packet.absYMin = device->absY.minimum;
    packet.absYMax = device->absY.maximum;
    packet.absXResolution = device->absX.resolution;
    packet.absYResolution = device->absY.resolution;
    memcpy(packet.eventData, data, dataSize);

    // Copy the full packet into our buffer
    memcpy(packetBuffer, &packetSize, sizeof(packetSize));
    memcpy(&packetBuffer[sizeof(packetSize)], &packet, packetSize);

    // Lock to prevent other threads from sending at the same time
    pthread_mutex_lock(&SocketSendLock);
    send(sock, packetBuffer, packetSize + sizeof(packetSize), 0);
    pthread_mutex_unlock(&SocketSendLock);
}

void* pollThreadFunc(void* context) {
    struct DeviceEntry *device = context;
    struct pollfd pollinfo;
    int pollres, ret;
    char data[EVDEV_MAX_EVENT_SIZE];

    __android_log_print(ANDROID_LOG_INFO, "EvdevReader", "Polling /dev/input/%s", device->devName);

    if (grabbing) {
        // Exclusively grab the input device (required to make the Android cursor disappear)
        if (ioctl(device->fd, EVIOCGRAB, 1) < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "EvdevReader",
                                "EVIOCGRAB failed for %s: %d", device->devName, errno);
            goto cleanup;
        }
    }

    for (;;) {
        do {
            // Unwait every 250 ms to return to caller if the fd is closed
            pollinfo.fd = device->fd;
            pollinfo.events = POLLIN;
            pollinfo.revents = 0;
            pollres = poll(&pollinfo, 1, 250);
        }
        while (pollres == 0);

        if (pollres > 0 && (pollinfo.revents & POLLIN)) {
            // We'll have data available now
            ret = read(device->fd, data, EVDEV_MAX_EVENT_SIZE);
            if (ret < 0) {
                __android_log_print(ANDROID_LOG_ERROR, "EvdevReader",
                                    "read() failed: %d", errno);
                goto cleanup;
            }
            else if (ret == 0) {
                __android_log_print(ANDROID_LOG_ERROR, "EvdevReader",
                                    "read() graceful EOF");
                goto cleanup;
            }
            else if (grabbing) {
                // Write out the data to our client
                outputEvdevData(device, data, ret);
            }
        }
        else {
            if (pollres < 0) {
                __android_log_print(ANDROID_LOG_ERROR, "EvdevReader",
                                    "poll() failed: %d", errno);
            }
            else {
                __android_log_print(ANDROID_LOG_ERROR, "EvdevReader",
                                    "Unexpected revents: %d", pollinfo.revents);
            }

            // Terminate this thread
            goto cleanup;
        }
    }

cleanup:
    __android_log_print(ANDROID_LOG_INFO, "EvdevReader", "Closing /dev/input/%s", device->devName);

    // Remove the context from the linked list
    {
        struct DeviceEntry *lastEntry;

        // Lock the device list
        pthread_mutex_lock(&DeviceListLock);

        if (DeviceListHead == device) {
            DeviceListHead = device->next;
        }
        else {
            lastEntry = DeviceListHead;
            while (lastEntry->next != NULL) {
                if (lastEntry->next == device) {
                    lastEntry->next = device->next;
                    break;
                }

                lastEntry = lastEntry->next;
            }
        }

        // Unlock device list
        pthread_mutex_unlock(&DeviceListLock);
    }

    // Free the context
    ioctl(device->fd, EVIOCGRAB, 0);
    close(device->fd);
    free(device);

    return NULL;
}

static int precheckDeviceForPolling(int fd) {
    int isMouse;
    int isKeyboard;
    int isGamepad;
    int isTouchpad;
    int deviceClass = 0;

    // This is the same check that Android does in EventHub.cpp
    isMouse = hasRelAxis(fd, REL_X) &&
           hasRelAxis(fd, REL_Y) &&
           hasKey(fd, BTN_LEFT);

    // This is the same check that Android does in EventHub.cpp
    isKeyboard = hasKey(fd, KEY_Q);

    isGamepad = hasKey(fd, BTN_GAMEPAD);

    isTouchpad = hasAbsAxis(fd, ABS_MT_SLOT) &&
            hasAbsAxis(fd, ABS_MT_TRACKING_ID) &&
            hasAbsAxis(fd, ABS_MT_POSITION_X) &&
            hasAbsAxis(fd, ABS_MT_POSITION_Y) &&
            hasInputProp(fd, INPUT_PROP_POINTER);

    if (isMouse) {
        deviceClass |= EVDEV_DEVICE_MOUSE;
    }
    if (isKeyboard) {
        deviceClass |= EVDEV_DEVICE_KEYBOARD;
    }
    if (isTouchpad && optimizeTouchpad) {
        deviceClass |= EVDEV_DEVICE_TOUCHPAD;
    }

    // We only handle keyboards, mice, and touchpads that aren't gamepads.
    // When touchpad optimization is disabled, a pure touchpad (no REL/key mouse
    // capabilities) reports deviceClass 0 here, so we leave it untouched and
    // Android handles it as a normal pointer device.
    return isGamepad ? 0 : deviceClass;
}

static void startPollForDevice(char* deviceName) {
    struct DeviceEntry *currentEntry;
    char fullPath[256];
    int fd;
    int deviceClass;

    // Lock the device list
    pthread_mutex_lock(&DeviceListLock);

    // Check if the device is already being polled
    currentEntry = DeviceListHead;
    while (currentEntry != NULL) {
        if (strcmp(currentEntry->devName, deviceName) == 0) {
            // Already polling this device
            goto unlock;
        }

        currentEntry = currentEntry->next;
    }

    // Open the device
    sprintf(fullPath, "/dev/input/%s", deviceName);
    fd = open(fullPath, O_RDWR);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "EvdevReader", "Couldn't open %s: %d", fullPath, errno);
        goto unlock;
    }

    // Allocate a context
    currentEntry = malloc(sizeof(*currentEntry));
    if (currentEntry == NULL) {
        close(fd);
        goto unlock;
    }

    // Check if we support polling this device
    deviceClass = precheckDeviceForPolling(fd);
    if (!deviceClass) {
        // Nope, get out
        free(currentEntry);
        close(fd);
        goto unlock;
    }

    // Populate context
    memset(currentEntry, 0, sizeof(*currentEntry));
    currentEntry->fd = fd;
    currentEntry->deviceId = NextDeviceId++;
    currentEntry->deviceClass = deviceClass;
    strcpy(currentEntry->devName, deviceName);
    if (deviceClass & EVDEV_DEVICE_TOUCHPAD) {
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &currentEntry->absX);
        ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &currentEntry->absY);
    }

    // Start the polling thread
    if (pthread_create(&currentEntry->thread, NULL, pollThreadFunc, currentEntry) != 0) {
        free(currentEntry);
        close(fd);
        goto unlock;
    }

    // Queue this onto the device list
    currentEntry->next = DeviceListHead;
    DeviceListHead = currentEntry;

unlock:
    // Unlock and return
    pthread_mutex_unlock(&DeviceListLock);
}

static int enumerateDevices(void) {
    DIR *inputDir;
    struct dirent *dirEnt;

    inputDir = opendir("/dev/input");
    if (!inputDir) {
        __android_log_print(ANDROID_LOG_ERROR, "EvdevReader", "Couldn't open /dev/input: %d", errno);
        return -1;
    }

    // Start polling each device in /dev/input
    while ((dirEnt = readdir(inputDir)) != NULL) {
        if (strcmp(dirEnt->d_name, ".") == 0 || strcmp(dirEnt->d_name, "..") == 0) {
            // Skip these virtual directories
            continue;
        }

        if (strstr(dirEnt->d_name, "event") == NULL) {
            // Skip non-event devices
            continue;
        }

        startPollForDevice(dirEnt->d_name);
    }

    closedir(inputDir);
    return 0;
}

static int connectSocket(int port) {
    struct sockaddr_in saddr;
    int ret;
    int val;

    sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (sock < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "EvdevReader", "socket() failed: %d", errno);
        return -1;
    }

    memset(&saddr, 0, sizeof(saddr));
    saddr.sin_family = AF_INET;
    saddr.sin_port = htons(port);
    saddr.sin_addr.s_addr = inet_addr("127.0.0.1");
    ret = connect(sock, (struct sockaddr*)&saddr, sizeof(saddr));
    if (ret < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "EvdevReader", "connect() failed: %d", errno);
        return -1;
    }

    val = 1;
    ret = setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, (char*)&val, sizeof(val));
    if (ret < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "EvdevReader", "setsockopt() failed: %d", errno);
        // We can continue anyways
    }

    __android_log_print(ANDROID_LOG_INFO, "EvdevReader", "Connection established to port %d", port);

    return 0;
}

#define UNGRAB_REQ 1
#define REGRAB_REQ 2

int main(int argc, char* argv[]) {
    int ret;
    int pollres;
    struct pollfd pollinfo;
    int port;

    __android_log_print(ANDROID_LOG_INFO, "EvdevReader", "Entered main()");

    port = atoi(argv[1]);
    __android_log_print(ANDROID_LOG_INFO, "EvdevReader", "Requested port number: %d", port);

    // Optional second argument: 1 to stream hardware touchpads, 0 to leave them
    // to Android (native mouse). Defaults to enabled for backward compatibility.
    if (argc > 2) {
        optimizeTouchpad = atoi(argv[2]);
    }
    __android_log_print(ANDROID_LOG_INFO, "EvdevReader", "Touchpad optimization: %s",
        optimizeTouchpad ? "enabled" : "disabled");

    // Connect to the app's socket
    ret = connectSocket(port);
    if (ret < 0) {
        return ret;
    }

    // Perform initial enumeration
    ret = enumerateDevices();
    if (ret < 0) {
        return ret;
    }

    // Wait for requests from the client
    for (;;) {
        unsigned char requestId;

        do {
            // Every second we poll again for new devices if
            // we haven't received any new events
            pollinfo.fd = sock;
            pollinfo.events = POLLIN;
            pollinfo.revents = 0;
            pollres = poll(&pollinfo, 1, 1000);
            if (pollres == 0) {
                // Timeout, re-enumerate devices
                enumerateDevices();
            }
        }
        while (pollres == 0);

        if (pollres > 0 && (pollinfo.revents & POLLIN)) {
            // We'll have data available now
            ret = recv(sock, &requestId, sizeof(requestId), 0);
            if (ret < sizeof(requestId)) {
                __android_log_print(ANDROID_LOG_ERROR, "EvdevReader", "Short read on socket");
                return errno;
            }

            if (requestId != UNGRAB_REQ && requestId != REGRAB_REQ) {
                __android_log_print(ANDROID_LOG_ERROR, "EvdevReader", "Unknown request");
                return requestId;
            }

            {
                struct DeviceEntry *currentEntry;

                pthread_mutex_lock(&DeviceListLock);

                // Update state for future devices
                grabbing = (requestId == REGRAB_REQ);

                // Carry out the requested action on each device
                currentEntry = DeviceListHead;
                while (currentEntry != NULL) {
                    ioctl(currentEntry->fd, EVIOCGRAB, grabbing);
                    currentEntry = currentEntry->next;
                }

                pthread_mutex_unlock(&DeviceListLock);

                __android_log_print(ANDROID_LOG_INFO, "EvdevReader", "New grab status is: %s",
                    grabbing ? "enabled" : "disabled");
            }
        }
        else {
            // Terminate this thread
            if (pollres < 0) {
                __android_log_print(ANDROID_LOG_ERROR, "EvdevReader",
                                    "Socket recv poll() failed: %d", errno);
            }
            else {
                __android_log_print(ANDROID_LOG_ERROR, "EvdevReader",
                                    "Socket poll unexpected revents: %d", pollinfo.revents);
            }

            return -1;
        }
    }
}
