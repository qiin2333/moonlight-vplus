#include "framegen_pipeline.hpp"

#include <android/log.h>
#include <volk.h>
#include <vulkan/vulkan_core.h>

#include <lsfg_3_1.hpp>

#include "extract/extract.hpp"
#include "extract/trans.hpp"

#include "yuv_to_rgba.comp.spv.h"

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#define LOG_TAG "Framegen"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

namespace FramegenPipeline {
namespace {

enum class ProbeState : uint8_t {
    kUninitialized = 0,
    kReady,
    kUnsupported,
};

enum class ContextBootState : uint8_t {
    kUninitialized = 0,
    kReady,
    kFailed,
};

struct AhbDeleter {
    void operator()(AHardwareBuffer* buffer) const {
        if (buffer != nullptr) {
            AHardwareBuffer_release(buffer);
        }
    }
};

using AhbPtr = std::unique_ptr<AHardwareBuffer, AhbDeleter>;

struct VulkanContext {
    VkInstance instance{VK_NULL_HANDLE};
    VkPhysicalDevice physicalDevice{VK_NULL_HANDLE};
    VkDevice device{VK_NULL_HANDLE};
    VkQueue queue{VK_NULL_HANDLE};
    uint32_t queueFamilyIndex{UINT32_MAX};
    VkCommandPool cmdPool{VK_NULL_HANDLE};
    VkPhysicalDeviceMemoryProperties memProps{};

    ~VulkanContext() {
        if (device != VK_NULL_HANDLE) {
            if (cmdPool != VK_NULL_HANDLE) {
                vkDestroyCommandPool(device, cmdPool, nullptr);
            }
            vkDestroyDevice(device, nullptr);
        }
        if (instance != VK_NULL_HANDLE) {
            vkDestroyInstance(instance, nullptr);
        }
    }
};

struct ContextResources {
    AhbPtr input0;
    AhbPtr input1;
    std::vector<AhbPtr> outputs;
    int32_t contextId{-1};
    uint32_t width{0};
    uint32_t height{0};

    // 阶段 3.3a-iii.a：owned input AHB import 后的 VkImage / memory / view。
    // 这些资源跟 AHB 绑定，整个 framegen 生命周期内不变。
    VkDevice ownerDevice{VK_NULL_HANDLE};
    VkImage input0Image{VK_NULL_HANDLE};
    VkDeviceMemory input0Memory{VK_NULL_HANDLE};
    VkImageView input0View{VK_NULL_HANDLE};
    VkImage input1Image{VK_NULL_HANDLE};
    VkDeviceMemory input1Memory{VK_NULL_HANDLE};
    VkImageView input1View{VK_NULL_HANDLE};

    // 阶段 3.3a-iii.a：YUV→RGBA compute pipeline（不含 ycbcr conversion；
    // conversion 与 decoder external format 绑定，要等首帧到达后才在 3.3a-iii.b 创建）。
    VkShaderModule shaderModule{VK_NULL_HANDLE};

    // 阶段 3.3a-iii.b.1：首帧到达后才创建的 ycbcr conversion + descriptor 套件 + compute pipeline。
    // conversion 绑 decoder externalFormat，所以必须延后到第一帧才能建。
    uint64_t boundExternalFormat{0};
    VkSamplerYcbcrConversion ycbcrConversion{VK_NULL_HANDLE};
    VkSampler ycbcrSampler{VK_NULL_HANDLE};
    VkDescriptorSetLayout dsLayout{VK_NULL_HANDLE};
    VkPipelineLayout pipelineLayout{VK_NULL_HANDLE};
    VkPipeline pipeline{VK_NULL_HANDLE};

    ~ContextResources() {
        if (ownerDevice == VK_NULL_HANDLE) {
            return;
        }
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(ownerDevice, pipeline, nullptr);
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(ownerDevice, pipelineLayout, nullptr);
        }
        if (dsLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(ownerDevice, dsLayout, nullptr);
        }
        if (ycbcrSampler != VK_NULL_HANDLE) {
            vkDestroySampler(ownerDevice, ycbcrSampler, nullptr);
        }
        if (ycbcrConversion != VK_NULL_HANDLE) {
            vkDestroySamplerYcbcrConversion(ownerDevice, ycbcrConversion, nullptr);
        }
        if (shaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(ownerDevice, shaderModule, nullptr);
        }
        if (input0View != VK_NULL_HANDLE) {
            vkDestroyImageView(ownerDevice, input0View, nullptr);
        }
        if (input1View != VK_NULL_HANDLE) {
            vkDestroyImageView(ownerDevice, input1View, nullptr);
        }
        if (input0Image != VK_NULL_HANDLE) {
            vkDestroyImage(ownerDevice, input0Image, nullptr);
        }
        if (input1Image != VK_NULL_HANDLE) {
            vkDestroyImage(ownerDevice, input1Image, nullptr);
        }
        if (input0Memory != VK_NULL_HANDLE) {
            vkFreeMemory(ownerDevice, input0Memory, nullptr);
        }
        if (input1Memory != VK_NULL_HANDLE) {
            vkFreeMemory(ownerDevice, input1Memory, nullptr);
        }
    }
};

std::atomic<ProbeState> g_probeState{ProbeState::kUninitialized};
std::atomic<ContextBootState> g_contextBootState{ContextBootState::kUninitialized};
std::mutex g_contextMutex;
std::unique_ptr<VulkanContext> g_vk;
std::unique_ptr<ContextResources> g_context;

constexpr uint64_t kFirstAvailableDeviceUuid = 0x1463ABACULL;
constexpr uint32_t kGenerationCount = 1; // 2x 插帧：每两帧之间生成 1 帧。

AhbPtr allocateOwnedRgbaAhb(uint32_t width, uint32_t height) {
    AHardwareBuffer_Desc desc{
        .width = width,
        .height = height,
        .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
                 AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
        .stride = 0,
        .rfu0 = 0,
        .rfu1 = 0,
    };

    AHardwareBuffer* raw = nullptr;
    if (AHardwareBuffer_allocate(&desc, &raw) != 0 || raw == nullptr) {
        throw std::runtime_error("AHardwareBuffer_allocate failed");
    }
    return AhbPtr(raw);
}

// 把已经分配的 owned RGBA AHB 导入成 VkImage + VkDeviceMemory + VkImageView。
// 这跟 probeImportDecoderAhb 不同：因为 AHB 格式是已知的 R8G8B8A8_UNORM，所以
// VkImage.format = VK_FORMAT_R8G8B8A8_UNORM 且不需要 VkExternalFormatANDROID，
// 也不需要 ycbcr conversion，可以直接作为 storage image 被 compute shader 写。
struct ImportedImage {
    VkImage image{VK_NULL_HANDLE};
    VkDeviceMemory memory{VK_NULL_HANDLE};
    VkImageView view{VK_NULL_HANDLE};
};

ImportedImage importOwnedRgbaAhb(VkDevice device,
                                 const VkPhysicalDeviceMemoryProperties& memProps,
                                 AHardwareBuffer* ahb,
                                 uint32_t width, uint32_t height) {
    VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID,
        .pNext = nullptr,
    };
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
        .pNext = &fmtProps,
    };
    if (vkGetAndroidHardwareBufferPropertiesANDROID(device, ahb, &ahbProps) != VK_SUCCESS) {
        throw std::runtime_error("vkGetAHBPropsAndroid (owned RGBA) failed");
    }

    VkExternalMemoryImageCreateInfo extImg{
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .pNext = nullptr,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    VkImageCreateInfo imgCi{
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &extImg,
        .flags = 0,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .extent = { width, height, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_STORAGE_BIT |
                 VK_IMAGE_USAGE_SAMPLED_BIT |
                 VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    ImportedImage out{};
    if (vkCreateImage(device, &imgCi, nullptr, &out.image) != VK_SUCCESS) {
        throw std::runtime_error("vkCreateImage (owned RGBA AHB) failed");
    }

    uint32_t typeIndex = UINT32_MAX;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if (ahbProps.memoryTypeBits & (1u << i)) {
            typeIndex = i;
            break;
        }
    }
    if (typeIndex == UINT32_MAX) {
        vkDestroyImage(device, out.image, nullptr);
        throw std::runtime_error("no compatible memory type for owned RGBA AHB");
    }

    VkMemoryDedicatedAllocateInfo dedicated{
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = nullptr,
        .image = out.image,
        .buffer = VK_NULL_HANDLE,
    };
    VkImportAndroidHardwareBufferInfoANDROID importInfo{
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .pNext = &dedicated,
        .buffer = ahb,
    };
    VkMemoryAllocateInfo alloc{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &importInfo,
        .allocationSize = ahbProps.allocationSize,
        .memoryTypeIndex = typeIndex,
    };
    if (vkAllocateMemory(device, &alloc, nullptr, &out.memory) != VK_SUCCESS) {
        vkDestroyImage(device, out.image, nullptr);
        throw std::runtime_error("vkAllocateMemory (owned RGBA AHB) failed");
    }
    if (vkBindImageMemory(device, out.image, out.memory, 0) != VK_SUCCESS) {
        vkFreeMemory(device, out.memory, nullptr);
        vkDestroyImage(device, out.image, nullptr);
        throw std::runtime_error("vkBindImageMemory (owned RGBA AHB) failed");
    }

    VkImageViewCreateInfo viewCi{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .image = out.image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                       VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY},
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
    if (vkCreateImageView(device, &viewCi, nullptr, &out.view) != VK_SUCCESS) {
        vkFreeMemory(device, out.memory, nullptr);
        vkDestroyImage(device, out.image, nullptr);
        throw std::runtime_error("vkCreateImageView (owned RGBA AHB) failed");
    }
    return out;
}

std::vector<uint8_t> loadTranslatedShader(const std::string& name) {
    return Extract::translateShader(Extract::getShader(name));
}

bool hasDeviceExtension(VkPhysicalDevice physicalDevice, const char* extName) {
    uint32_t extCount = 0;
    if (vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extCount, nullptr) != VK_SUCCESS) {
        return false;
    }

    std::vector<VkExtensionProperties> exts(extCount);
    if (extCount > 0 &&
        vkEnumerateDeviceExtensionProperties(physicalDevice, nullptr, &extCount, exts.data()) != VK_SUCCESS) {
        return false;
    }

    for (const auto& ext : exts) {
        if (std::strcmp(ext.extensionName, extName) == 0) {
            return true;
        }
    }
    return false;
}

bool probeVulkanAhbSupport() {
    if (volkInitialize() != VK_SUCCESS) {
        LOGE("stage3.2 probe: volkInitialize failed");
        return false;
    }

    const VkApplicationInfo appInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pNext = nullptr,
        .pApplicationName = "moonlight-framegen-probe",
        .applicationVersion = 1,
        .pEngineName = "moonlight",
        .engineVersion = 1,
        .apiVersion = VK_API_VERSION_1_1,
    };

    const VkInstanceCreateInfo instanceCi{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .pApplicationInfo = &appInfo,
        .enabledLayerCount = 0,
        .ppEnabledLayerNames = nullptr,
        .enabledExtensionCount = 0,
        .ppEnabledExtensionNames = nullptr,
    };

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&instanceCi, nullptr, &instance) != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        LOGE("stage3.2 probe: vkCreateInstance failed");
        return false;
    }

    volkLoadInstance(instance);

    uint32_t gpuCount = 0;
    if (vkEnumeratePhysicalDevices(instance, &gpuCount, nullptr) != VK_SUCCESS || gpuCount == 0) {
        LOGE("stage3.2 probe: no physical devices");
        vkDestroyInstance(instance, nullptr);
        return false;
    }

    std::vector<VkPhysicalDevice> gpus(gpuCount);
    if (vkEnumeratePhysicalDevices(instance, &gpuCount, gpus.data()) != VK_SUCCESS) {
        LOGE("stage3.2 probe: enumerate physical devices failed");
        vkDestroyInstance(instance, nullptr);
        return false;
    }

    bool ok = false;
    for (auto gpu : gpus) {
        const bool hasExternalMemory = hasDeviceExtension(gpu, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
        const bool hasDedicatedAlloc = hasDeviceExtension(gpu, VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME);
        const bool hasGetMemReq2 = hasDeviceExtension(gpu, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME);
        const bool hasExternalMemoryAhb = hasDeviceExtension(gpu, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);

        if (hasExternalMemory && hasDedicatedAlloc && hasGetMemReq2 && hasExternalMemoryAhb) {
            ok = true;
            break;
        }
    }

    LOGI("stage3.2 probe: Vulkan AHB import %s", ok ? "READY" : "UNSUPPORTED");
    vkDestroyInstance(instance, nullptr);
    return ok;
}

// Build the long-lived VkInstance + VkDevice + queue + command pool that
// will own per-frame AHB-import work for stage 3.3. This is created lazily
// inside bootstrapContext() so we never pay for it if framegen is off.
std::unique_ptr<VulkanContext> buildVulkanContext() {
    auto ctx = std::make_unique<VulkanContext>();

    const VkApplicationInfo appInfo{
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pNext = nullptr,
        .pApplicationName = "moonlight-framegen",
        .applicationVersion = 1,
        .pEngineName = "moonlight",
        .engineVersion = 1,
        .apiVersion = VK_API_VERSION_1_1,
    };
    const VkInstanceCreateInfo instanceCi{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .pApplicationInfo = &appInfo,
        .enabledLayerCount = 0,
        .ppEnabledLayerNames = nullptr,
        .enabledExtensionCount = 0,
        .ppEnabledExtensionNames = nullptr,
    };
    if (vkCreateInstance(&instanceCi, nullptr, &ctx->instance) != VK_SUCCESS) {
        throw std::runtime_error("vkCreateInstance (long-lived) failed");
    }
    volkLoadInstance(ctx->instance);

    uint32_t gpuCount = 0;
    if (vkEnumeratePhysicalDevices(ctx->instance, &gpuCount, nullptr) != VK_SUCCESS || gpuCount == 0) {
        throw std::runtime_error("no Vulkan physical devices");
    }
    std::vector<VkPhysicalDevice> gpus(gpuCount);
    vkEnumeratePhysicalDevices(ctx->instance, &gpuCount, gpus.data());

    static const char* const kRequiredExts[] = {
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
        VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
        VK_KHR_MAINTENANCE1_EXTENSION_NAME,
        VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
        VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
    };

    for (auto gpu : gpus) {
        bool allOk = true;
        for (auto* name : kRequiredExts) {
            if (!hasDeviceExtension(gpu, name)) {
                allOk = false;
                break;
            }
        }
        if (!allOk) {
            continue;
        }
        ctx->physicalDevice = gpu;
        break;
    }
    if (ctx->physicalDevice == VK_NULL_HANDLE) {
        throw std::runtime_error("no GPU exposes required AHB+YCbCr extensions");
    }

    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->physicalDevice, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qfProps(qfCount);
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->physicalDevice, &qfCount, qfProps.data());

    for (uint32_t i = 0; i < qfCount; ++i) {
        if (qfProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            ctx->queueFamilyIndex = i;
            break;
        }
    }
    if (ctx->queueFamilyIndex == UINT32_MAX) {
        throw std::runtime_error("no graphics queue family on chosen GPU");
    }

    const float queuePriority = 1.0F;
    const VkDeviceQueueCreateInfo queueCi{
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .queueFamilyIndex = ctx->queueFamilyIndex,
        .queueCount = 1,
        .pQueuePriorities = &queuePriority,
    };

    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures{
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
        .pNext = nullptr,
        .samplerYcbcrConversion = VK_TRUE,
    };

    const VkDeviceCreateInfo deviceCi{
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = &ycbcrFeatures,
        .flags = 0,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queueCi,
        .enabledLayerCount = 0,
        .ppEnabledLayerNames = nullptr,
        .enabledExtensionCount = static_cast<uint32_t>(std::size(kRequiredExts)),
        .ppEnabledExtensionNames = kRequiredExts,
        .pEnabledFeatures = nullptr,
    };
    if (vkCreateDevice(ctx->physicalDevice, &deviceCi, nullptr, &ctx->device) != VK_SUCCESS) {
        throw std::runtime_error("vkCreateDevice (long-lived) failed");
    }
    volkLoadDevice(ctx->device);

    vkGetDeviceQueue(ctx->device, ctx->queueFamilyIndex, 0, &ctx->queue);

    const VkCommandPoolCreateInfo poolCi{
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = ctx->queueFamilyIndex,
    };
    if (vkCreateCommandPool(ctx->device, &poolCi, nullptr, &ctx->cmdPool) != VK_SUCCESS) {
        throw std::runtime_error("vkCreateCommandPool failed");
    }

    vkGetPhysicalDeviceMemoryProperties(ctx->physicalDevice, &ctx->memProps);

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(ctx->physicalDevice, &props);
    LOGI("stage3.3a: long-lived VkDevice ready gpu=\"%s\" qf=%u driver=0x%x apiVer=%u.%u.%u",
         props.deviceName, ctx->queueFamilyIndex, props.driverVersion,
         VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion),
         VK_VERSION_PATCH(props.apiVersion));

    return ctx;
}

} // namespace

bool ensureVulkanAhbReady(AHardwareBuffer* ahb, int width, int height, int format) {
    if (ahb == nullptr) {
        return false;
    }

    const ProbeState state = g_probeState.load(std::memory_order_acquire);
    if (state == ProbeState::kReady) {
        return true;
    }
    if (state == ProbeState::kUnsupported) {
        return false;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    LOGI("stage3.2 probe start: reader=%dx%d/fmt=%d ahb=%ux%u/fmt=0x%x/usage=0x%llx",
         width, height, format,
         desc.width, desc.height, desc.format,
         static_cast<unsigned long long>(desc.usage));

    const bool ready = probeVulkanAhbSupport();
    g_probeState.store(ready ? ProbeState::kReady : ProbeState::kUnsupported, std::memory_order_release);
    return ready;
}

bool ensureContextBootstrapped(AHardwareBuffer* decoderAhb, int width, int height, int format) {
    if (!ensureVulkanAhbReady(decoderAhb, width, height, format)) {
        return false;
    }

    const ContextBootState state = g_contextBootState.load(std::memory_order_acquire);
    if (state == ContextBootState::kReady) {
        return true;
    }
    if (state == ContextBootState::kFailed) {
        return false;
    }

    std::lock_guard<std::mutex> lock(g_contextMutex);
    const ContextBootState stateAfterLock = g_contextBootState.load(std::memory_order_acquire);
    if (stateAfterLock == ContextBootState::kReady) {
        return true;
    }
    if (stateAfterLock == ContextBootState::kFailed) {
        return false;
    }

    try {
        AHardwareBuffer_Desc decoderDesc{};
        AHardwareBuffer_describe(decoderAhb, &decoderDesc);
        const uint32_t ctxWidth = decoderDesc.width != 0 ? decoderDesc.width : static_cast<uint32_t>(width);
        const uint32_t ctxHeight = decoderDesc.height != 0 ? decoderDesc.height : static_cast<uint32_t>(height);

        LOGI("stage3.2 bootstrap start: owned RGBA AHB context %ux%u (decoder fmt=0x%x usage=0x%llx)",
             ctxWidth, ctxHeight, decoderDesc.format,
             static_cast<unsigned long long>(decoderDesc.usage));

        if (g_vk == nullptr) {
            g_vk = buildVulkanContext();
        }

        Extract::extractShaders();

        // 先创建 LSFG 自己的 AHB 共享上下文。注意：这里还没有把 decoder AHB copy 到 owned input，
        // 所以后续 submit 仍保持关闭；这一步只验证 device/shader/context 三件事能否真正建立。
        auto resources = std::make_unique<ContextResources>();
        resources->width = ctxWidth;
        resources->height = ctxHeight;
        resources->input0 = allocateOwnedRgbaAhb(ctxWidth, ctxHeight);
        resources->input1 = allocateOwnedRgbaAhb(ctxWidth, ctxHeight);
        resources->outputs.emplace_back(allocateOwnedRgbaAhb(ctxWidth, ctxHeight));

        std::vector<AHardwareBuffer*> outputAhbs;
        outputAhbs.reserve(resources->outputs.size());
        for (const auto& output : resources->outputs) {
            outputAhbs.push_back(output.get());
        }

        setenv("DISABLE_LSFG", "1", 1); // NOLINT(concurrency-mt-unsafe)
        LSFG_3_1::initialize(
            kFirstAvailableDeviceUuid,
            false,
            1.0F,
            kGenerationCount,
            loadTranslatedShader);

        resources->contextId = LSFG_3_1::createContextFromAHB(
            resources->input0.get(),
            resources->input1.get(),
            outputAhbs,
            VkExtent2D{ctxWidth, ctxHeight},
            VK_FORMAT_R8G8B8A8_UNORM);
        unsetenv("DISABLE_LSFG"); // NOLINT(concurrency-mt-unsafe)

        // 阶段 3.3a-iii.a：把 owned input0/input1 RGBA AHB 同步 import 到我们自己的 VkDevice，
        // 这样后面 compute shader 可以把 YUV decoder 帧解码写到这些 VkImage 上，LSFG 通过 AHB
        // 共享自动看到内容。output AHB 暂不在我们这边 import，由 LSFG 内部 device 持有。
        resources->ownerDevice = g_vk->device;
        {
            auto in0 = importOwnedRgbaAhb(g_vk->device, g_vk->memProps,
                                          resources->input0.get(), ctxWidth, ctxHeight);
            resources->input0Image = in0.image;
            resources->input0Memory = in0.memory;
            resources->input0View = in0.view;
            auto in1 = importOwnedRgbaAhb(g_vk->device, g_vk->memProps,
                                          resources->input1.get(), ctxWidth, ctxHeight);
            resources->input1Image = in1.image;
            resources->input1Memory = in1.memory;
            resources->input1View = in1.view;
        }

        // 编译内嵌的 YUV→RGBA compute shader（descriptor 套件需要 ycbcr conversion，
        // 留到 iii.b 接到首帧时再建）。
        {
            VkShaderModuleCreateInfo smCi{
                .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .codeSize = k_yuv_to_rgba_spv_size,
                .pCode = k_yuv_to_rgba_spv,
            };
            if (vkCreateShaderModule(g_vk->device, &smCi, nullptr, &resources->shaderModule) != VK_SUCCESS) {
                throw std::runtime_error("vkCreateShaderModule (yuv_to_rgba) failed");
            }
        }
        LOGI("stage3.3a-iii.a: owned RGBA AHB imported to VkImage + shader module ok");

        g_context = std::move(resources);

        std::vector<int> noOutputSemaphores;
        LSFG_3_1::presentContext(g_context->contextId, -1, noOutputSemaphores);
        LSFG_3_1::waitIdle();

        LOGI("stage3.2 bootstrap ok: lsfg context id=%d outputs=%zu submit-smoke=ok",
             g_context->contextId, g_context->outputs.size());

        g_contextBootState.store(ContextBootState::kReady, std::memory_order_release);
        return true;
    } catch (const std::exception& e) {
        unsetenv("DISABLE_LSFG"); // NOLINT(concurrency-mt-unsafe)
        LOGE("stage3.2 bootstrap failed: %s", e.what());
        LSFG_3_1::finalize();
        g_context.reset();
        if (g_vk != nullptr && g_vk->device != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(g_vk->device);
        }
        g_vk.reset();
        g_contextBootState.store(ContextBootState::kFailed, std::memory_order_release);
        return false;
    }
}

void reset() {
    std::lock_guard<std::mutex> lock(g_contextMutex);
    if (g_context != nullptr) {
        LOGI("stage3.2 reset: deleting lsfg context id=%d", g_context->contextId);
    }
    LSFG_3_1::finalize();
    g_context.reset();
    if (g_vk != nullptr && g_vk->device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(g_vk->device);
    }
    g_vk.reset();
    g_probeState.store(ProbeState::kUninitialized, std::memory_order_release);
    g_contextBootState.store(ContextBootState::kUninitialized, std::memory_order_release);
}

// 阶段 3.3a-iii.b.1：用首帧的 externalFormat 创建延后到运行时的 YCbCr conversion +
// sampler + descriptor set layout + compute pipeline。一次性创建后整个 framegen
// 生命周期内复用。必须在 g_contextMutex 持锁下调用。
bool ensureYcbcrPipelineLocked(const VkAndroidHardwareBufferFormatPropertiesANDROID& fp) {
    if (g_context == nullptr || g_vk == nullptr) {
        return false;
    }
    if (g_context->pipeline != VK_NULL_HANDLE) {
        // 已经建过 — 假定后续 decoder AHB 的 externalFormat 不会变。如果变了我们再处理。
        if (g_context->boundExternalFormat != fp.externalFormat) {
            LOGE("stage3.3a-iii.b.1: externalFormat changed 0x%llx -> 0x%llx (not supported yet)",
                 static_cast<unsigned long long>(g_context->boundExternalFormat),
                 static_cast<unsigned long long>(fp.externalFormat));
        }
        return true;
    }
    if (fp.externalFormat == 0) {
        LOGE("stage3.3a-iii.b.1: externalFormat=0 cannot build conversion");
        return false;
    }

    VkDevice device = g_vk->device;

    // 1. YCbCr conversion（external format 绑定）。
    VkExternalFormatANDROID extFmt{
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
        .pNext = nullptr,
        .externalFormat = fp.externalFormat,
    };
    VkSamplerYcbcrConversionCreateInfo cvtCi{
        .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO,
        .pNext = &extFmt,
        .format = VK_FORMAT_UNDEFINED,
        .ycbcrModel = fp.suggestedYcbcrModel,
        .ycbcrRange = fp.suggestedYcbcrRange,
        .components = fp.samplerYcbcrConversionComponents,
        .xChromaOffset = fp.suggestedXChromaOffset,
        .yChromaOffset = fp.suggestedYChromaOffset,
        .chromaFilter = (fp.formatFeatures &
                         VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT)
                            ? VK_FILTER_LINEAR
                            : VK_FILTER_NEAREST,
        .forceExplicitReconstruction = VK_FALSE,
    };
    if (vkCreateSamplerYcbcrConversion(device, &cvtCi, nullptr, &g_context->ycbcrConversion) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkCreateSamplerYcbcrConversion failed");
        return false;
    }

    // 2. sampler 绑 conversion。
    VkSamplerYcbcrConversionInfo cvtInfo{
        .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
        .pNext = nullptr,
        .conversion = g_context->ycbcrConversion,
    };
    VkSamplerCreateInfo samplerCi{
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .pNext = &cvtInfo,
        .flags = 0,
        .magFilter = cvtCi.chromaFilter,
        .minFilter = cvtCi.chromaFilter,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .mipLodBias = 0.0F,
        .anisotropyEnable = VK_FALSE,
        .maxAnisotropy = 1.0F,
        .compareEnable = VK_FALSE,
        .compareOp = VK_COMPARE_OP_NEVER,
        .minLod = 0.0F,
        .maxLod = 0.0F,
        .borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK,
        .unnormalizedCoordinates = VK_FALSE,
    };
    if (vkCreateSampler(device, &samplerCi, nullptr, &g_context->ycbcrSampler) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkCreateSampler failed");
        return false;
    }

    // 3. descriptor set layout：binding 0 = combined image sampler with immutable ycbcr sampler，
    // binding 1 = storage image (R8G8B8A8_UNORM)。
    VkSampler immutable = g_context->ycbcrSampler;
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[0].pImmutableSamplers = &immutable;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].pImmutableSamplers = nullptr;
    VkDescriptorSetLayoutCreateInfo dslCi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .bindingCount = 2,
        .pBindings = bindings,
    };
    if (vkCreateDescriptorSetLayout(device, &dslCi, nullptr, &g_context->dsLayout) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkCreateDescriptorSetLayout failed");
        return false;
    }

    // 4. pipeline layout（无 push constants，待 iii.b.2 再加）。
    VkPipelineLayoutCreateInfo plCi{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .setLayoutCount = 1,
        .pSetLayouts = &g_context->dsLayout,
        .pushConstantRangeCount = 0,
        .pPushConstantRanges = nullptr,
    };
    if (vkCreatePipelineLayout(device, &plCi, nullptr, &g_context->pipelineLayout) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkCreatePipelineLayout failed");
        return false;
    }

    // 5. compute pipeline。
    VkComputePipelineCreateInfo pipeCi{
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .stage = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = g_context->shaderModule,
            .pName = "main",
            .pSpecializationInfo = nullptr,
        },
        .layout = g_context->pipelineLayout,
        .basePipelineHandle = VK_NULL_HANDLE,
        .basePipelineIndex = -1,
    };
    if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeCi, nullptr, &g_context->pipeline) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkCreateComputePipelines failed");
        return false;
    }

    g_context->boundExternalFormat = fp.externalFormat;
    LOGI("stage3.3a-iii.b.1: ycbcr conversion + sampler + pipeline ready "
         "externalFormat=0x%llx model=%u range=%u chromaFilter=%d",
         static_cast<unsigned long long>(fp.externalFormat),
         static_cast<unsigned>(fp.suggestedYcbcrModel),
         static_cast<unsigned>(fp.suggestedYcbcrRange),
         static_cast<int>(cvtCi.chromaFilter));
    return true;
}

bool probeImportDecoderAhb(AHardwareBuffer* decoderAhb) {
    if (decoderAhb == nullptr) {
        return false;
    }
    if (g_contextBootState.load(std::memory_order_acquire) != ContextBootState::kReady) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_contextMutex);
    if (g_vk == nullptr || g_vk->device == VK_NULL_HANDLE) {
        return false;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID,
        .pNext = nullptr,
    };
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
        .pNext = &formatProps,
    };
    VkResult res = vkGetAndroidHardwareBufferPropertiesANDROID(g_vk->device, decoderAhb, &ahbProps);
    if (res != VK_SUCCESS) {
        LOGE("stage3.3a-ii: vkGetAndroidHardwareBufferPropertiesANDROID failed res=%d", res);
        return false;
    }

    // iii.b.1: 首次接收到 decoder AHB 时建立 ycbcr conversion + compute pipeline。
    // 失败不阻断 probe，让 iii.a 的 import-only 路径仍可观察。
    ensureYcbcrPipelineLocked(formatProps);

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(decoderAhb, &desc);

    VkExternalFormatANDROID extFmt{
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID,
        .pNext = nullptr,
        .externalFormat = formatProps.externalFormat,
    };
    VkExternalMemoryImageCreateInfo extImg{
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .pNext = &extFmt,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    VkImageCreateInfo imgCi{
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &extImg,
        .flags = 0,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_UNDEFINED,
        .extent = { desc.width, desc.height, 1 },
        .mipLevels = 1,
        .arrayLayers = desc.layers > 0 ? desc.layers : 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 0,
        .pQueueFamilyIndices = nullptr,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };

    VkImage image = VK_NULL_HANDLE;
    res = vkCreateImage(g_vk->device, &imgCi, nullptr, &image);
    if (res != VK_SUCCESS) {
        LOGE("stage3.3a-ii: vkCreateImage (external) failed res=%d externalFormat=0x%llx",
             res, static_cast<unsigned long long>(formatProps.externalFormat));
        return false;
    }

    uint32_t typeIndex = UINT32_MAX;
    for (uint32_t i = 0; i < g_vk->memProps.memoryTypeCount; ++i) {
        if (ahbProps.memoryTypeBits & (1u << i)) {
            typeIndex = i;
            break;
        }
    }
    if (typeIndex == UINT32_MAX) {
        LOGE("stage3.3a-ii: no compatible memory type (memBits=0x%x)", ahbProps.memoryTypeBits);
        vkDestroyImage(g_vk->device, image, nullptr);
        return false;
    }

    VkMemoryDedicatedAllocateInfo dedicated{
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = nullptr,
        .image = image,
        .buffer = VK_NULL_HANDLE,
    };
    VkImportAndroidHardwareBufferInfoANDROID importInfo{
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .pNext = &dedicated,
        .buffer = decoderAhb,
    };
    VkMemoryAllocateInfo alloc{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &importInfo,
        .allocationSize = ahbProps.allocationSize,
        .memoryTypeIndex = typeIndex,
    };
    VkDeviceMemory memory = VK_NULL_HANDLE;
    res = vkAllocateMemory(g_vk->device, &alloc, nullptr, &memory);
    if (res != VK_SUCCESS) {
        LOGE("stage3.3a-ii: vkAllocateMemory (AHB import) failed res=%d size=%llu",
             res, static_cast<unsigned long long>(ahbProps.allocationSize));
        vkDestroyImage(g_vk->device, image, nullptr);
        return false;
    }

    res = vkBindImageMemory(g_vk->device, image, memory, 0);
    if (res != VK_SUCCESS) {
        LOGE("stage3.3a-ii: vkBindImageMemory failed res=%d", res);
        vkFreeMemory(g_vk->device, memory, nullptr);
        vkDestroyImage(g_vk->device, image, nullptr);
        return false;
    }

    LOGI("stage3.3a-ii: decoder AHB import ok externalFormat=0x%llx size=%llu memBits=0x%x "
         "samplerYcbcrFeatures=0x%x",
         static_cast<unsigned long long>(formatProps.externalFormat),
         static_cast<unsigned long long>(ahbProps.allocationSize),
         ahbProps.memoryTypeBits,
         formatProps.formatFeatures);

    vkFreeMemory(g_vk->device, memory, nullptr);
    vkDestroyImage(g_vk->device, image, nullptr);
    return true;
}

} // namespace FramegenPipeline
