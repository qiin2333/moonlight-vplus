// SPDX-License-Identifier: GPL-3.0-or-later
// FD export approach follows yunsmall/Android-Usbipdcpp (GPL-3.0).
#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <libusb.h>
#include <usbipdcpp/LibusbHandler/LibusbServer.h>
#include <usbipdcpp/LibusbHandler/tools.h>
#include <memory>
#include <mutex>
#include <atomic>
#include <cstdint>
#include <stdexcept>
#include <unordered_map>

namespace {
// One exporter per exported device. Nothing here is process-global device
// state: each instance owns its listener, its device FD, and the single source
// port its own tunnel is allowed to connect from, so sharing one device cannot
// disturb another. Handles are never reused, so a stale handle can only ever
// miss.
struct Instance {
    std::unique_ptr<usbipdcpp::LibusbServer> server;
    std::atomic_uint16_t authorizedSourcePort{0};
    std::uint16_t port = 0;
    int deviceFd = -1;
};

std::mutex mutex;
std::unordered_map<jlong, std::unique_ptr<Instance>> instances;
jlong nextHandle = 1;
// libusb's default context is process-wide and reference counted. It lives for
// as long as any exporter does: every instance wraps its own Android FD into
// that one context and runs its own event thread over it.
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

/** Stops the exporter and closes the duplicated FD; caller holds mutex. */
void teardown(Instance& instance) {
    // Stop waits for URB callbacks before the FD is released.
    if (instance.server) { instance.server->stop(); instance.server.reset(); }
    if (instance.deviceFd >= 0) { close(instance.deviceFd); instance.deviceFd = -1; }
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
            std::uint16_t expected = peer.port();
            return expected != 0 && owner->authorizedSourcePort.compare_exchange_strong(
                    expected, 0, std::memory_order_acq_rel);
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
    instance->authorizedSourcePort.store(static_cast<std::uint16_t>(sourcePort), std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_usbip_NativeUsbIp_revokeLocalConnection(JNIEnv*, jclass, jlong handle, jint sourcePort) {
    if (sourcePort < 1 || sourcePort > 65535) return;
    std::lock_guard lock(mutex);
    Instance* instance = find(handle);
    // An exporter that is already gone took its authorization with it.
    if (!instance) return;
    std::uint16_t expected = static_cast<std::uint16_t>(sourcePort);
    instance->authorizedSourcePort.compare_exchange_strong(expected, 0, std::memory_order_acq_rel);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_usbip_NativeUsbIp_bind(JNIEnv* env, jclass, jlong handle, jint fd) {
    std::lock_guard lock(mutex);
    Instance* instance = find(handle);
    try {
        if (!instance || instance->deviceFd >= 0) throw std::runtime_error("Invalid USB/IP binding state");
        int ownedFd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (ownedFd < 0) throw std::runtime_error("Unable to duplicate USB FD");
        // Wrapped handles never close the FD, so the duplicate stays owned here
        // and can be re-wrapped for every client connection.
        instance->deviceFd = ownedFd;
        libusb_device_handle* device = nullptr;
        if (libusb_wrap_sys_device(nullptr, ownedFd, &device) != 0)
            throw std::runtime_error("Unable to wrap USB FD");
        std::unique_ptr<libusb_device_handle, decltype(&libusb_close)> wrapped(device, libusb_close);
        const auto busId = usbipdcpp::get_device_busid(libusb_get_device(device));
        wrapped.reset();
        if (instance->server->bind_host_device_with_wrapped_fd(ownedFd) != usbipdcpp::DeviceOperationResult::Success)
            throw std::runtime_error("Unable to bind USB device");
        jstring result = env->NewStringUTF(busId.c_str());
        if (!result) {
            // Allocation failed mid-bind: drop this exporter here since the
            // caller has no handle left to release it with.
            jthrowable pending = env->ExceptionOccurred();
            if (pending) env->ExceptionClear();
            teardown(*instance);
            instances.erase(handle);
            releaseLibusb();
            if (pending) env->Throw(pending);
        }
        return result;
    } catch (const std::exception& error) {
        // The duplicated FD stays owned by this instance: callers release it by
        // stopping the exporter, which preserves the original connection until
        // the stop succeeds.
        fail(env, error.what());
        return nullptr;
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
