// SPDX-License-Identifier: GPL-3.0-or-later
// FD export approach follows yunsmall/Android-Usbipdcpp (GPL-3.0).
#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <libusb.h>
#include <usbipdcpp/LibusbHandler/LibusbServer.h>
#include <usbipdcpp/LibusbHandler/tools.h>
#include <chrono>
#include <memory>
#include <mutex>
#include <shared_mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>

namespace {
// One exporter serves any number of devices, each bound by its own busid, the
// way a desktop usbipd does. A handle is never reused, so a stale handle can
// only ever miss.
struct Instance {
    std::unique_ptr<usbipdcpp::LibusbServer> server;
    // busid -> duplicated FD. The library never closes it, so it stays valid
    // until the device is unbound, and can be re-wrapped per client connection.
    std::unordered_map<std::string, int> devices;
    // The connection filter runs on the server's network thread, which stop()
    // joins while holding the global mutex: it must never take that mutex, or
    // stopping an exporter would deadlock against its own accept loop.
    std::mutex filterMutex;
    std::unordered_set<std::uint16_t> authorizedPorts;
    std::uint16_t port = 0;
};

// Releasing a device closes its tunnel first, so its session usually drains on
// its own; the tiers below only cover a session that does not.
constexpr auto kDrainTimeout = std::chrono::milliseconds(1500);
constexpr auto kForceTimeout = std::chrono::milliseconds(2500);
constexpr auto kPollInterval = std::chrono::milliseconds(2);

std::mutex mutex;
std::unordered_map<jlong, std::unique_ptr<Instance>> instances;
jlong nextHandle = 1;
// libusb's default context is process-wide and reference counted. It lives for
// as long as any exporter does.
int libusbReferences = 0;

void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/io/IOException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}

// Caller holds mutex.
Instance* find(jlong handle) {
    auto found = instances.find(handle);
    return found == instances.end() ? nullptr : found->second.get();
}

void acquireLibusb() {
    if (libusbReferences > 0) { ++libusbReferences; return; }
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (libusb_init(nullptr) != 0) throw std::runtime_error("libusb initialization failed");
    libusbReferences = 1;
}

void releaseLibusb() {
    if (--libusbReferences == 0) libusb_exit(nullptr);
}

bool deviceInUse(usbipdcpp::Server& server, const std::string& busid) {
    std::shared_lock lock(server.get_devices_mutex());
    return server.get_using_devices().contains(busid);
}

/** Polls a condition that a session teardown completes on another thread. */
template <typename Condition>
bool waitFor(Condition done, std::chrono::milliseconds timeout) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (!done()) {
        if (std::chrono::steady_clock::now() >= deadline) return false;
        std::this_thread::sleep_for(kPollInterval);
    }
    return true;
}

void closeDevices(Instance& instance) {
    for (const auto& device : instance.devices) close(device.second);
    instance.devices.clear();
}

/** Stops the exporter and closes every FD it still owns; caller holds mutex. */
void teardown(Instance& instance) {
    // Stop waits for URB callbacks before the FDs are released.
    if (instance.server) { instance.server->stop(); instance.server.reset(); }
    closeDevices(instance);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_limelight_usbip_NativeUsbIp_start(JNIEnv* env, jclass) {
    std::lock_guard lock(mutex);
    jlong handle = 0;
    bool acquired = false;
    try {
        // Register before starting anything: the entry address is stable, so the
        // connection filter below can capture it, and every failure path can find
        // what it has to tear down.
        handle = nextHandle++;
        Instance& instance = *instances.emplace(handle, std::make_unique<Instance>()).first->second;
        acquireLibusb();
        acquired = true;
        instance.server = std::make_unique<usbipdcpp::LibusbServer>();
        instance.server->set_hotplug_enabled(false);
        // The filter outlives every connection: it is only ever called while the
        // server is running, and the server is destroyed before the instance.
        Instance* owner = &instance;
        instance.server->get_server().set_connection_filter([owner](const asio::ip::tcp::endpoint& peer) {
            if (!peer.address().is_loopback()) return false;
            const std::uint16_t source = peer.port();
            if (source == 0) return false;
            // Each tunnel authorizes its own source port and spends it here.
            std::lock_guard filterLock(owner->filterMutex);
            return owner->authorizedPorts.erase(source) == 1;
        });
        asio::ip::tcp::endpoint endpoint(asio::ip::address_v4::loopback(), 0);
        if (auto error = instance.server->start(endpoint))
            throw std::runtime_error(error.message());
        instance.port = endpoint.port();
    } catch (const std::exception& error) {
        auto registered = instances.find(handle);
        if (registered != instances.end()) {
            try { teardown(*registered->second); } catch (...) { /* report the original failure */ }
            instances.erase(registered);
        }
        if (acquired) releaseLibusb();
        fail(env, error.what());
        return 0;
    }
    return handle;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_usbip_NativeUsbIp_localPort(JNIEnv* env, jclass, jlong handle) {
    std::lock_guard lock(mutex);
    Instance* instance = find(handle);
    if (!instance) {
        fail(env, "Invalid USB/IP handle");
        return 0;
    }
    return instance->port;
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_usbip_NativeUsbIp_authorizeLocalConnection(JNIEnv* env, jclass, jlong handle,
                                                              jint sourcePort) {
    std::lock_guard lock(mutex);
    Instance* instance = find(handle);
    if (!instance || sourcePort < 1 || sourcePort > 65535) {
        fail(env, "Invalid USB/IP local connection authorization");
        return;
    }
    std::lock_guard filterLock(instance->filterMutex);
    instance->authorizedPorts.insert(static_cast<std::uint16_t>(sourcePort));
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_usbip_NativeUsbIp_revokeLocalConnection(JNIEnv*, jclass, jlong handle, jint sourcePort) {
    if (sourcePort < 1 || sourcePort > 65535) return;
    std::lock_guard lock(mutex);
    Instance* instance = find(handle);
    // An exporter that is already gone took its authorizations with it.
    if (!instance) return;
    std::lock_guard filterLock(instance->filterMutex);
    instance->authorizedPorts.erase(static_cast<std::uint16_t>(sourcePort));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_usbip_NativeUsbIp_bind(JNIEnv* env, jclass, jlong handle, jint fd) {
    std::lock_guard lock(mutex);
    Instance* instance = find(handle);
    int ownedFd = -1;
    try {
        if (!instance || !instance->server) throw std::runtime_error("Invalid USB/IP binding state");
        ownedFd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (ownedFd < 0) throw std::runtime_error("Unable to duplicate USB FD");
        libusb_device_handle* device = nullptr;
        if (libusb_wrap_sys_device(nullptr, ownedFd, &device) != 0)
            throw std::runtime_error("Unable to wrap USB FD");
        // The wrapper only reads identity here: the library wraps the FD again
        // for every client connection.
        std::unique_ptr<libusb_device_handle, decltype(&libusb_close)> wrapped(device, libusb_close);
        const std::string busId = usbipdcpp::get_device_busid(libusb_get_device(device));
        wrapped.reset();
        // Own the FD before binding it: no failure may leave the library holding
        // an FD this map does not know about.
        if (!instance->devices.emplace(busId, ownedFd).second)
            throw std::runtime_error("USB device is already exported");
        try {
            if (instance->server->bind_host_device_with_wrapped_fd(ownedFd)
                    != usbipdcpp::DeviceOperationResult::Success)
                throw std::runtime_error("Unable to bind USB device");
            jstring result = env->NewStringUTF(busId.c_str());
            if (!result) throw std::runtime_error("Unable to report the USB busid");
            return result;
        } catch (...) {
            // Undo everything for a device the caller never learned about.
            instance->server->notify_device_removed(busId);
            instance->devices.erase(busId);
            throw;
        }
    } catch (const std::exception& error) {
        if (ownedFd >= 0) close(ownedFd);
        fail(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_usbip_NativeUsbIp_unbind(JNIEnv* env, jclass, jlong handle, jstring busId) {
    std::lock_guard lock(mutex);
    Instance* instance = find(handle);
    if (!instance || !instance->server) {
        fail(env, "Invalid USB/IP handle");
        return;
    }
    if (!busId) {
        fail(env, "Invalid USB/IP busid");
        return;
    }
    const char* raw = env->GetStringUTFChars(busId, nullptr);
    if (!raw) return;   // pending OutOfMemoryError
    const std::string busid(raw);
    env->ReleaseStringUTFChars(busId, raw);
    auto found = instance->devices.find(busid);
    if (found == instance->devices.end()) return;   // already released
    const int deviceFd = found->second;

    usbipdcpp::Server& server = instance->server->get_server();
    try {
        // The tunnel is closed before this call, so the session normally drains
        // by itself; otherwise it is forced to stop, and the FD can only be
        // closed once the device has left both device lists.
        if (waitFor([&] { return !deviceInUse(server, busid); }, kDrainTimeout)) {
            // Drained: drop it from the available list, releasing whatever the
            // session did not.
            auto result = instance->server->unbind_host_device_by_fd(deviceFd);
            if (result != usbipdcpp::DeviceOperationResult::Success
                    && result != usbipdcpp::DeviceOperationResult::DeviceNotFound)
                throw std::runtime_error("Unable to unbind USB device");
        } else {
            instance->server->notify_device_removed(busid);
            if (!waitFor([&] { return !server.has_bound_device(busid); }, kForceTimeout))
                throw std::runtime_error("USB device did not drain");
        }
        close(deviceFd);
        instance->devices.erase(found);
    } catch (const std::exception& error) {
        // Keep the FD and the registration: the handler may still wrap it.
        fail(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_usbip_NativeUsbIp_stop(JNIEnv* env, jclass, jlong handle) {
    std::lock_guard lock(mutex);
    auto found = instances.find(handle);
    if (found == instances.end()) return;
    try {
        teardown(*found->second);
    } catch (const std::exception& error) {
        // Keep the instance registered: the FD owner is still live, and a later
        // stop may still succeed.
        fail(env, error.what());
        return;
    }
    instances.erase(found);
    releaseLibusb();
}
