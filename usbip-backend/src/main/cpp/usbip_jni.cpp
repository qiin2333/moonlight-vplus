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
#include <stdexcept>

namespace {
std::mutex mutex;
std::unique_ptr<usbipdcpp::LibusbServer> server;
int deviceFd = -1;
bool initialized = false;
std::atomic_uint16_t authorizedSourcePort{0};
void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/io/IOException");
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
void stop() {
    authorizedSourcePort.store(0, std::memory_order_release);
    if (server) { server->stop(); server.reset(); }
    if (deviceFd >= 0) { close(deviceFd); deviceFd = -1; }
    if (initialized) { libusb_exit(nullptr); initialized = false; }
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_usbip_NativeUsbIp_start(JNIEnv* env, jclass) {
    std::lock_guard lock(mutex);
    try {
        if (server) throw std::runtime_error("USB/IP already running");
        libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
        if (libusb_init(nullptr) != 0) throw std::runtime_error("libusb initialization failed");
        initialized = true;
        server = std::make_unique<usbipdcpp::LibusbServer>();
        server->set_hotplug_enabled(false);
        server->get_server().set_connection_filter([](const asio::ip::tcp::endpoint& peer) {
            if (!peer.address().is_loopback()) return false;
            std::uint16_t expected = peer.port();
            return expected != 0 && authorizedSourcePort.compare_exchange_strong(
                    expected, 0, std::memory_order_acq_rel);
        });
        asio::ip::tcp::endpoint endpoint(asio::ip::address_v4::loopback(), 0);
        if (auto ec = server->start(endpoint); ec)
            throw std::runtime_error(ec.message());
        return endpoint.port();
    } catch (const std::exception& error) {
        stop(); fail(env, error.what()); return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_usbip_NativeUsbIp_authorizeLocalConnection(JNIEnv* env, jclass, jint sourcePort) {
    std::lock_guard lock(mutex);
    if (!server || sourcePort < 1 || sourcePort > 65535) {
        fail(env, "Invalid USB/IP local connection authorization");
        return;
    }
    authorizedSourcePort.store(static_cast<std::uint16_t>(sourcePort), std::memory_order_release);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_usbip_NativeUsbIp_bind(JNIEnv* env, jclass, jint fd) {
    std::lock_guard lock(mutex);
    try {
        if (!server || deviceFd >= 0) throw std::runtime_error("Invalid USB/IP binding state");
        int ownedFd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (ownedFd < 0) throw std::runtime_error("Unable to duplicate USB FD");
        deviceFd = ownedFd;
        libusb_device_handle* handle = nullptr;
        if (libusb_wrap_sys_device(nullptr, deviceFd, &handle) != 0)
            throw std::runtime_error("Unable to wrap USB FD");
        std::unique_ptr<libusb_device_handle, decltype(&libusb_close)> wrapped(handle, libusb_close);
        const auto busId = usbipdcpp::get_device_busid(libusb_get_device(handle));
        wrapped.reset();
        if (server->bind_host_device_with_wrapped_fd(deviceFd) != usbipdcpp::DeviceOperationResult::Success)
            throw std::runtime_error("Unable to bind USB device");
        jstring result = env->NewStringUTF(busId.c_str());
        if (!result) stop();
        return result;
    } catch (const std::exception& error) {
        // Caller subsequently invokes stop, preserving the original connection until drained.
        fail(env, error.what()); return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_usbip_NativeUsbIp_stop(JNIEnv* env, jclass) {
    std::lock_guard lock(mutex);
    try { stop(); }
    catch (const std::exception& error) { fail(env, error.what()); }
}
