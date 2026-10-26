// DS5 UAC output uses USBFS ISO URBs. Android UsbRequest rejects ISO endpoints.
// This is the sole asynchronous USB owner on this driver connection; HID uses
// synchronous bulkTransfer. The descriptor remains open until every URB retires.
#ifdef USB_ISO_MOCK_TEST
#include "UsbIsochronousMockPlatform.h"
#else
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <poll.h>
#include <pthread.h>
#endif
#include <stdlib.h>
#include <stdint.h>
#include <errno.h>

#define JNI_NAME(name) Java_com_limelight_nvstream_jni_MoonBridge_##name
#define MAX_SLOTS 16

typedef struct {
    int fd, count, size, closing;
    pthread_mutex_t lock;
    struct usbdevfs_urb* urb[MAX_SLOTS];
    jobject buffer[MAX_SLOTS];
    int pending[MAX_SLOTS];
} ISO_OUTPUT;

JNIEXPORT jlong JNICALL JNI_NAME(createUsbIsochronousOutput)(JNIEnv* env, jclass cls,
        jint fd, jint endpoint, jint slots, jint size) {
    (void)env; (void)cls;
    if (fd < 0 || endpoint < 1 || endpoint > 15 || slots < 1 || slots > MAX_SLOTS ||
            size < 1 || size > 1024) return 0;
    ISO_OUTPUT* output = calloc(1, sizeof(*output));
    if (output == NULL) return 0;
    output->fd = dup(fd);
    if (output->fd < 0) { free(output); return 0; }
    pthread_mutex_init(&output->lock, NULL);
    output->count = slots;
    output->size = size;
    for (int i = 0; i < slots; i++) {
        output->urb[i] = calloc(1, sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc));
        if (output->urb[i] == NULL) {
            for (int j = 0; j < i; j++) free(output->urb[j]);
            pthread_mutex_destroy(&output->lock);
            close(output->fd);
            free(output);
            return 0;
        }
        output->urb[i]->type = USBDEVFS_URB_TYPE_ISO;
        output->urb[i]->endpoint = endpoint;
        output->urb[i]->flags = USBDEVFS_URB_ISO_ASAP;
        output->urb[i]->buffer_length = size;
        output->urb[i]->number_of_packets = 1;
        output->urb[i]->iso_frame_desc[0].length = size;
    }
    return (jlong)(intptr_t)output;
}

JNIEXPORT jint JNICALL JNI_NAME(submitUsbIsochronousOutput)(JNIEnv* env, jclass cls,
        jlong handle, jint slot, jobject buffer) {
    (void)cls;
    ISO_OUTPUT* output = (ISO_OUTPUT*)(intptr_t)handle;
    if (output == NULL || slot < 0 || slot >= output->count || buffer == NULL) return -EINVAL;
    void* data = (*env)->GetDirectBufferAddress(env, buffer);
    if (data == NULL || (*env)->GetDirectBufferCapacity(env, buffer) < output->size) return -EINVAL;
    pthread_mutex_lock(&output->lock);
    int result;
    if (output->closing || output->pending[slot]) result = -EBUSY;
    else {
        if (output->buffer[slot] == NULL) output->buffer[slot] = (*env)->NewGlobalRef(env, buffer);
        if (output->buffer[slot] == NULL) {
            (*env)->ExceptionClear(env);
            result = -ENOMEM;
        }
        else if (!(*env)->IsSameObject(env, buffer, output->buffer[slot])) result = -EINVAL;
        else {
            output->urb[slot]->buffer = data;
            output->urb[slot]->status = 0;
            output->urb[slot]->iso_frame_desc[0].status = 0;
            output->urb[slot]->iso_frame_desc[0].actual_length = 0;
            do { result = ioctl(output->fd, USBDEVFS_SUBMITURB, output->urb[slot]); }
            while (result < 0 && errno == EINTR);
            if (result < 0) result = -errno;
            else output->pending[slot] = 1;
        }
    }
    pthread_mutex_unlock(&output->lock);
    return result;
}

JNIEXPORT jint JNICALL JNI_NAME(reapUsbIsochronousOutput)(JNIEnv* env, jclass cls,
        jlong handle, jint timeout) {
    (void)env; (void)cls;
    ISO_OUTPUT* output = (ISO_OUTPUT*)(intptr_t)handle;
    if (output == NULL || timeout < 0 || timeout > 1000) return -EINVAL;
    struct pollfd descriptor = { .fd = output->fd, .events = POLLOUT };
    int result;
    do { result = poll(&descriptor, 1, timeout); } while (result < 0 && errno == EINTR);
    if (result == 0) return -ETIMEDOUT;
    if (result < 0) return -errno;
    struct usbdevfs_urb* completed = NULL;
    do { result = ioctl(output->fd, USBDEVFS_REAPURBNDELAY, &completed); }
    while (result < 0 && errno == EINTR);
    if (result < 0) return errno == EAGAIN ? -ETIMEDOUT : -errno;
    pthread_mutex_lock(&output->lock);
    result = -EPROTO;
    for (int i = 0; i < output->count; i++) {
        if (completed != output->urb[i]) continue;
        output->pending[i] = 0;
        int packet_status = (int32_t)completed->iso_frame_desc[0].status;
        if (completed->status != 0) result = completed->status;
        else if (packet_status != 0) result = packet_status;
        else if (completed->iso_frame_desc[0].actual_length != (unsigned int)output->size) result = -EIO;
        else result = i;
        break;
    }
    pthread_mutex_unlock(&output->lock);
    return result;
}

static void cancel_output(ISO_OUTPUT* output) {
    pthread_mutex_lock(&output->lock);
    output->closing = 1;
    for (int i = 0; i < output->count; i++) {
        if (output->pending[i]) ioctl(output->fd, USBDEVFS_DISCARDURB, output->urb[i]);
    }
    pthread_mutex_unlock(&output->lock);
}

JNIEXPORT void JNICALL JNI_NAME(cancelUsbIsochronousOutput)(JNIEnv* env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    ISO_OUTPUT* output = (ISO_OUTPUT*)(intptr_t)handle;
    if (output != NULL) cancel_output(output);
}

// Called by the sender after it stops reaping, never concurrently with submit/reap/cancel.
JNIEXPORT jint JNICALL JNI_NAME(closeUsbIsochronousOutput)(JNIEnv* env, jclass cls, jlong handle) {
    (void)cls;
    ISO_OUTPUT* output = (ISO_OUTPUT*)(intptr_t)handle;
    if (output == NULL) return 0;
    cancel_output(output);
    for (;;) {
        int pending = 0;
        for (int i = 0; i < output->count; i++) pending += output->pending[i];
        if (!pending) break;
        struct usbdevfs_urb* completed = NULL;
        int result;
        do { result = ioctl(output->fd, USBDEVFS_REAPURB, &completed); }
        while (result < 0 && errno == EINTR);
        // ENODEV is returned only after the disconnected device's completion list drains.
        if (result < 0 && errno == ENODEV) break;
        if (result < 0) return -errno; // Keep URBs/buffers alive if ownership is uncertain.
        int found = 0;
        for (int i = 0; i < output->count; i++) {
            if (completed == output->urb[i]) { output->pending[i] = 0; found = 1; break; }
        }
        if (!found) return -EPROTO;
    }
    close(output->fd);
    for (int i = 0; i < output->count; i++) {
        free(output->urb[i]);
        if (output->buffer[i] != NULL) (*env)->DeleteGlobalRef(env, output->buffer[i]);
    }
    pthread_mutex_destroy(&output->lock);
    free(output);
    return 0;
}
