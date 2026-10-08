/* SPDX-License-Identifier: GPL-3.0-only */
#include "pyrowave_capabilities.h"

#include <dlfcn.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstring>
#include <vector>

namespace {

class BundleWriter {
 public:
  explicit BundleWriter(JNIEnv *env) : env(env) {
    type = env->FindClass("android/os/Bundle");
    if (type == nullptr) return;
    constructor = env->GetMethodID(type, "<init>", "()V");
    put_int = env->GetMethodID(type, "putInt", "(Ljava/lang/String;I)V");
    put_boolean = env->GetMethodID(type, "putBoolean", "(Ljava/lang/String;Z)V");
    put_string = env->GetMethodID(type, "putString", "(Ljava/lang/String;Ljava/lang/String;)V");
    put_array = env->GetMethodID(type, "putParcelableArray", "(Ljava/lang/String;[Landroid/os/Parcelable;)V");
  }

  ~BundleWriter() { if (type != nullptr) env->DeleteLocalRef(type); }

  jobject create() const {
    return env->ExceptionCheck() ? nullptr : env->NewObject(type, constructor);
  }

  void integer(jobject object, const char *name, uint32_t value) const {
    if (env->ExceptionCheck()) return;
    const auto key = env->NewStringUTF(name);
    if (key != nullptr) env->CallVoidMethod(object, put_int, key, static_cast<jint>(value));
    env->DeleteLocalRef(key);
  }

  void boolean(jobject object, const char *name, bool value) const {
    if (env->ExceptionCheck()) return;
    const auto key = env->NewStringUTF(name);
    if (key != nullptr) env->CallVoidMethod(object, put_boolean, key, value ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(key);
  }

  void string(jobject object, const char *name, const char *value) const {
    if (env->ExceptionCheck()) return;
    const auto key = env->NewStringUTF(name);
    if (key == nullptr) return;
    const auto text = env->NewStringUTF(value);
    if (key != nullptr && text != nullptr) env->CallVoidMethod(object, put_string, key, text);
    env->DeleteLocalRef(key);
    env->DeleteLocalRef(text);
  }

  void devices(jobject object, jobjectArray devices) const {
    if (env->ExceptionCheck()) return;
    const auto key = env->NewStringUTF("devices");
    if (key != nullptr) env->CallVoidMethod(object, put_array, key, devices);
    env->DeleteLocalRef(key);
  }

  jobjectArray device_array(uint32_t size) const {
    return env->ExceptionCheck() ? nullptr : env->NewObjectArray(static_cast<jsize>(size), type, nullptr);
  }

 private:
  JNIEnv *env;
  jclass type = nullptr;
  jmethodID constructor = nullptr;
  jmethodID put_int = nullptr;
  jmethodID put_boolean = nullptr;
  jmethodID put_string = nullptr;
  jmethodID put_array = nullptr;
};

struct VulkanProbe {
  void *library = nullptr;
  VkInstance instance = VK_NULL_HANDLE;
  PFN_vkDestroyInstance destroy_instance = nullptr;

  ~VulkanProbe() {
    if (instance != VK_NULL_HANDLE && destroy_instance != nullptr) {
      destroy_instance(instance, nullptr);
    }
    if (library != nullptr) dlclose(library);
  }
};

bool has_extension(const std::vector<VkExtensionProperties> &extensions, const char *name) {
  return std::any_of(extensions.begin(), extensions.end(), [name](const auto &extension) {
    return std::strcmp(extension.extensionName, name) == 0;
  });
}

}  // namespace

jobject query_pyrowave_capabilities(JNIEnv *env, bool runtime_available) {
  BundleWriter writer(env);
  const auto report = writer.create();
  if (report == nullptr) return nullptr;
  writer.boolean(report, "runtimeAvailable", runtime_available);
  const auto fail = [&](const char *error) {
    writer.string(report, "error", error);
    return report;
  };

  try {
    VulkanProbe probe;
    probe.library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (probe.library == nullptr) return fail("loader_unavailable");
    const auto proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(probe.library, "vkGetInstanceProcAddr"));
    if (proc == nullptr) return fail("query_failed");
    const auto create_instance = reinterpret_cast<PFN_vkCreateInstance>(proc(nullptr, "vkCreateInstance"));
    const auto enumerate_extensions = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
        proc(nullptr, "vkEnumerateInstanceExtensionProperties"));
    if (create_instance == nullptr || enumerate_extensions == nullptr) return fail("query_failed");

    uint32_t loader_version = VK_API_VERSION_1_0;
    const auto enumerate_version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        proc(nullptr, "vkEnumerateInstanceVersion"));
    if (enumerate_version != nullptr && enumerate_version(&loader_version) != VK_SUCCESS) return fail("query_failed");
    writer.integer(report, "loaderVersion", loader_version);

    uint32_t count = 0;
    if (enumerate_extensions(nullptr, &count, nullptr) != VK_SUCCESS) return fail("query_failed");
    std::vector<VkExtensionProperties> instance_extensions(count);
    if (count != 0 && enumerate_extensions(nullptr, &count, instance_extensions.data()) != VK_SUCCESS) {
      return fail("query_failed");
    }
    writer.boolean(report, "androidSurface", has_extension(instance_extensions, VK_KHR_SURFACE_EXTENSION_NAME) &&
        has_extension(instance_extensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME));
    writer.boolean(report, "swapchainColorspace", has_extension(instance_extensions, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME));

    const auto requested_version = std::min(loader_version, static_cast<uint32_t>(VK_API_VERSION_1_3));
    const bool properties2_extension = has_extension(instance_extensions, VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
    const char *extension = VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
    VkApplicationInfo application{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    application.pApplicationName = "moonlight-pyrowave-capabilities";
    application.apiVersion = requested_version;
    VkInstanceCreateInfo create_info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    create_info.pApplicationInfo = &application;
    if (requested_version < VK_API_VERSION_1_1 && properties2_extension) {
      create_info.enabledExtensionCount = 1;
      create_info.ppEnabledExtensionNames = &extension;
    }
    const auto result = create_instance(&create_info, nullptr, &probe.instance);
    if (result != VK_SUCCESS) {
      writer.integer(report, "vkResult", static_cast<uint32_t>(result));
      return fail("query_failed");
    }
    probe.destroy_instance = reinterpret_cast<PFN_vkDestroyInstance>(proc(probe.instance, "vkDestroyInstance"));
    const auto enumerate_devices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(proc(probe.instance, "vkEnumeratePhysicalDevices"));
    const auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(proc(probe.instance, "vkGetPhysicalDeviceProperties"));
    const auto extensions = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(proc(probe.instance, "vkEnumerateDeviceExtensionProperties"));
    const auto queues = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(proc(probe.instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    auto properties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(proc(probe.instance, "vkGetPhysicalDeviceProperties2"));
    auto features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(proc(probe.instance, "vkGetPhysicalDeviceFeatures2"));
    if (requested_version < VK_API_VERSION_1_1 && properties2_extension) {
      properties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(proc(probe.instance, "vkGetPhysicalDeviceProperties2KHR"));
      features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(proc(probe.instance, "vkGetPhysicalDeviceFeatures2KHR"));
    } else if (requested_version < VK_API_VERSION_1_1) {
      properties2 = nullptr;
      features2 = nullptr;
    }
    if ((requested_version >= VK_API_VERSION_1_1 || properties2_extension) &&
        (properties2 == nullptr || features2 == nullptr)) return fail("query_failed");
    if (probe.destroy_instance == nullptr || enumerate_devices == nullptr || properties == nullptr || extensions == nullptr || queues == nullptr) {
      return fail("query_failed");
    }
    if (enumerate_devices(probe.instance, &count, nullptr) != VK_SUCCESS) return fail("query_failed");
    if (count == 0) return fail("no_devices");
    std::vector<VkPhysicalDevice> devices(count);
    if (enumerate_devices(probe.instance, &count, devices.data()) != VK_SUCCESS) return fail("query_failed");
    const auto device_reports = writer.device_array(count);
    if (device_reports == nullptr) return nullptr;

    for (uint32_t i = 0; i < count; ++i) {
      VkPhysicalDeviceProperties props{};
      properties(devices[i], &props);
      uint32_t extension_count = 0;
      if (extensions(devices[i], nullptr, &extension_count, nullptr) != VK_SUCCESS) return fail("query_failed");
      std::vector<VkExtensionProperties> device_extensions(extension_count);
      if (extension_count != 0 && extensions(devices[i], nullptr, &extension_count, device_extensions.data()) != VK_SUCCESS) {
        return fail("query_failed");
      }
      const auto device = writer.create();
      if (device == nullptr) return nullptr;
      writer.string(device, "name", props.deviceName);
      writer.integer(device, "apiVersion", props.apiVersion);
      writer.integer(device, "maxTexelBufferElements", props.limits.maxTexelBufferElements);
      writer.integer(device, "maxComputeWorkGroupInvocations", props.limits.maxComputeWorkGroupInvocations);
      writer.integer(device, "maxComputeSharedMemorySize", props.limits.maxComputeSharedMemorySize);
      writer.boolean(device, "swapchain", has_extension(device_extensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME));
      writer.boolean(device, "hdrMetadata", has_extension(device_extensions, VK_EXT_HDR_METADATA_EXTENSION_NAME));

      uint32_t queue_count = 0;
      queues(devices[i], &queue_count, nullptr);
      std::vector<VkQueueFamilyProperties> families(queue_count);
      if (queue_count != 0) queues(devices[i], &queue_count, families.data());
      writer.boolean(device, "graphicsComputeQueue", std::any_of(families.begin(), families.end(), [](const auto &family) {
        const auto required = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
        return family.queueCount > 0 && (family.queueFlags & required) == required;
      }));

      const auto effective_version = std::min(requested_version, props.apiVersion);
      VkPhysicalDeviceSubgroupProperties subgroup{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
      VkPhysicalDeviceSubgroupSizeControlProperties size{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES};
      const bool has_size = effective_version >= VK_API_VERSION_1_3 ||
          has_extension(device_extensions, VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME);
      if (properties2 != nullptr && effective_version >= VK_API_VERSION_1_1) {
        VkPhysicalDeviceProperties2 extended{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        extended.pNext = &subgroup;
        if (has_size) subgroup.pNext = &size;
        properties2(devices[i], &extended);
      }
      writer.integer(device, "subgroupOperations", subgroup.supportedOperations);
      writer.integer(device, "subgroupStages", subgroup.supportedStages);
      writer.integer(device, "subgroupSize", subgroup.subgroupSize);
      writer.integer(device, "minSubgroupSize", size.minSubgroupSize);
      writer.integer(device, "maxSubgroupSize", size.maxSubgroupSize);
      writer.integer(device, "requiredSubgroupSizeStages", size.requiredSubgroupSizeStages);

      VkPhysicalDeviceSubgroupSizeControlFeatures size_features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_FEATURES};
      VkPhysicalDevice8BitStorageFeatures storage{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES};
      if (features2 != nullptr) {
        VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        void **next = &features.pNext;
        if (has_size) { *next = &size_features; next = &size_features.pNext; }
        if (effective_version >= VK_API_VERSION_1_2 || has_extension(device_extensions, VK_KHR_8BIT_STORAGE_EXTENSION_NAME)) {
          *next = &storage;
        }
        features2(devices[i], &features);
      }
      writer.boolean(device, "subgroupSizeControl", size_features.subgroupSizeControl);
      writer.boolean(device, "computeFullSubgroups", size_features.computeFullSubgroups);
      writer.boolean(device, "storageBuffer8BitAccess", storage.storageBuffer8BitAccess);
      env->SetObjectArrayElement(device_reports, static_cast<jsize>(i), device);
      env->DeleteLocalRef(device);
      if (env->ExceptionCheck()) return nullptr;
    }
    writer.devices(report, device_reports);
    env->DeleteLocalRef(device_reports);
  } catch (...) {
    return env->ExceptionCheck() ? nullptr : fail("query_failed");
  }
  return env->ExceptionCheck() ? nullptr : report;
}
