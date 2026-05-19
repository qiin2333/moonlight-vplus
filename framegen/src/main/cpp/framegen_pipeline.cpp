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

    // 阶段 3.3a-iii.b.2：descriptor pool + 2 个 ping-pong descriptor sets。
    // binding 1 (storage image dst) 在 pipeline 创建时一次性 pre-write 到 input0/input1View；
    // binding 0 (combined sampler src) 每次 dispatch 用本帧 decoder image view 覆盖。
    VkDescriptorPool dsPool{VK_NULL_HANDLE};
    VkDescriptorSet dsSets[2]{VK_NULL_HANDLE, VK_NULL_HANDLE};
    uint32_t pingPongIndex{0};
    uint64_t dispatchCount{0};

    ~ContextResources() {
        if (ownerDevice == VK_NULL_HANDLE) {
            return;
        }
        if (dsPool != VK_NULL_HANDLE) {
            // descriptor sets 自动随 pool 销毁。
            vkDestroyDescriptorPool(ownerDevice, dsPool, nullptr);
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
std::atomic<bool> g_hdrEnabled{false};
std::mutex g_contextMutex;
std::unique_ptr<VulkanContext> g_vk;
std::unique_ptr<ContextResources> g_context;

// 阶段 3.3c：output AHB → SurfaceView 回贴目标窗口。
// 受 g_contextMutex 保护（与 dispatch 路径互斥）。本侧通过 ANativeWindow_release 归还。
ANativeWindow* g_outputWindow = nullptr;
int32_t g_outputWindowConfiguredW = 0;
int32_t g_outputWindowConfiguredH = 0;
uint64_t g_blitCount = 0;

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
        const bool hdrEnabled = g_hdrEnabled.load(std::memory_order_acquire);
        LSFG_3_1::initialize(
            kFirstAvailableDeviceUuid,
            hdrEnabled,
            1.0F,
            kGenerationCount,
            loadTranslatedShader);
        LOGI("stage3.2 bootstrap: LSFG_3_1::initialize isHdr=%d generationCount=%u",
             static_cast<int>(hdrEnabled), kGenerationCount);

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

        // 之前这里调一次 LSFG presentContext 做 smoke，但会让 LSFG 内部 frameIdx 起点变成 1，
        // 与本侧 pingPongIndex 起点 0 错位（slot mismatch，3.3b 的插帧会读到错误 slot）。
        // 删掉 smoke：3.3b 的真实 dispatch+presentContext 已经覆盖了 LSFG 调用路径。

        LOGI("stage3.2 bootstrap ok: lsfg context id=%d outputs=%zu",
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
    if (g_outputWindow != nullptr) {
        ANativeWindow_release(g_outputWindow);
        g_outputWindow = nullptr;
        g_outputWindowConfiguredW = 0;
        g_outputWindowConfiguredH = 0;
        g_blitCount = 0;
    }
}

void setHdrEnabled(bool enabled) {
    const bool prev = g_hdrEnabled.exchange(enabled, std::memory_order_acq_rel);
    if (prev != enabled) {
        LOGI("setHdrEnabled: %d -> %d (effective on next bootstrap)",
             static_cast<int>(prev), static_cast<int>(enabled));
    }
}

void setOutputWindow(ANativeWindow* nativeWindow) {
    std::lock_guard<std::mutex> lock(g_contextMutex);
    if (g_outputWindow == nativeWindow) return;
    if (g_outputWindow != nullptr) {
        ANativeWindow_release(g_outputWindow);
    }
    g_outputWindow = nativeWindow;
    g_outputWindowConfiguredW = 0;
    g_outputWindowConfiguredH = 0;
    g_blitCount = 0;
    LOGI("stage3.3c: setOutputWindow window=%p", nativeWindow);
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

    // 5. compute pipeline。spec const 0 = IS_HDR：HDR 串流时开启 PQ→sRGB tonemap。
    const int32_t isHdrSpec = g_hdrEnabled.load(std::memory_order_acquire) ? 1 : 0;
    VkSpecializationMapEntry specEntry{
        .constantID = 0,
        .offset = 0,
        .size = sizeof(int32_t),
    };
    VkSpecializationInfo specInfo{
        .mapEntryCount = 1,
        .pMapEntries = &specEntry,
        .dataSize = sizeof(int32_t),
        .pData = &isHdrSpec,
    };
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
            .pSpecializationInfo = &specInfo,
        },
        .layout = g_context->pipelineLayout,
        .basePipelineHandle = VK_NULL_HANDLE,
        .basePipelineIndex = -1,
    };
    if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeCi, nullptr, &g_context->pipeline) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkCreateComputePipelines failed");
        return false;
    }
    LOGI("stage3.3a-iii.b.1: pipeline ready IS_HDR=%d", isHdrSpec);

    // 6. descriptor pool + 2 个 ping-pong 集合，并预绑 binding 1 = input{0,1}View（GENERAL）。
    // binding 0 (combined sampler with immutable ycbcr sampler) 每帧 dispatch 时再覆盖。
    VkDescriptorPoolSize poolSizes[2]{
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2 },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2 },
    };
    VkDescriptorPoolCreateInfo dpCi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .maxSets = 2,
        .poolSizeCount = 2,
        .pPoolSizes = poolSizes,
    };
    if (vkCreateDescriptorPool(device, &dpCi, nullptr, &g_context->dsPool) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkCreateDescriptorPool failed");
        return false;
    }

    VkDescriptorSetLayout setLayouts[2] = { g_context->dsLayout, g_context->dsLayout };
    VkDescriptorSetAllocateInfo dsAi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .pNext = nullptr,
        .descriptorPool = g_context->dsPool,
        .descriptorSetCount = 2,
        .pSetLayouts = setLayouts,
    };
    if (vkAllocateDescriptorSets(device, &dsAi, g_context->dsSets) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkAllocateDescriptorSets failed");
        return false;
    }

    VkDescriptorImageInfo dstInfos[2]{
        { VK_NULL_HANDLE, g_context->input0View, VK_IMAGE_LAYOUT_GENERAL },
        { VK_NULL_HANDLE, g_context->input1View, VK_IMAGE_LAYOUT_GENERAL },
    };
    VkWriteDescriptorSet preWrites[2]{};
    for (int i = 0; i < 2; ++i) {
        preWrites[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        preWrites[i].dstSet = g_context->dsSets[i];
        preWrites[i].dstBinding = 1;
        preWrites[i].descriptorCount = 1;
        preWrites[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        preWrites[i].pImageInfo = &dstInfos[i];
    }
    vkUpdateDescriptorSets(device, 2, preWrites, 0, nullptr);

    g_context->boundExternalFormat = fp.externalFormat;
    LOGI("stage3.3a-iii.b.1: ycbcr conversion + sampler + pipeline ready "
         "externalFormat=0x%llx model=%u range=%u chromaFilter=%d",
         static_cast<unsigned long long>(fp.externalFormat),
         static_cast<unsigned>(fp.suggestedYcbcrModel),
         static_cast<unsigned>(fp.suggestedYcbcrRange),
         static_cast<int>(cvtCi.chromaFilter));
    return true;
}

// 阶段 3.3c：把指定 RGBA8888 AHB 通过 CPU 拷贝回贴到 SurfaceView 的 ANativeWindow。
// 调用方必须持 g_contextMutex 且 AHB 内容已 GPU 完成（dispatch 后 vkWaitForFences /
// LSFG waitIdle）。失败只打日志，不影响后续帧。tag 仅用于日志区分。
void blitAhbToWindowLocked(AHardwareBuffer* srcAhb, const char* tag) {
    if (g_outputWindow == nullptr || srcAhb == nullptr) {
        return;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(srcAhb, &desc);
    const int32_t srcW = static_cast<int32_t>(desc.width);
    const int32_t srcH = static_cast<int32_t>(desc.height);
    const int32_t srcStridePx = static_cast<int32_t>(desc.stride);
    if (srcW <= 0 || srcH <= 0) return;

    // 首次或尺寸变化时重配窗口。WINDOW_FORMAT_RGBA_8888 与 output AHB 格式一致。
    if (g_outputWindowConfiguredW != srcW || g_outputWindowConfiguredH != srcH) {
        const int32_t rc = ANativeWindow_setBuffersGeometry(
            g_outputWindow, srcW, srcH, WINDOW_FORMAT_RGBA_8888);
        if (rc != 0) {
            LOGE("stage3.3c: ANativeWindow_setBuffersGeometry rc=%d w=%d h=%d", rc, srcW, srcH);
            return;
        }
        // 显式声明数据空间为标准 sRGB，让 SurfaceFlinger 把 buffer 当 sRGB→display
        // 做色域管理；否则宽色域 OLED 屏会把 sRGB 数据按 native gamut 直显，
        // 视觉上颜色过饱和。ADATASPACE_SRGB = STANDARD_BT709 | TRANSFER_SRGB | RANGE_FULL。
        constexpr int32_t kAdataspaceSrgb = 142671872;
        ANativeWindow_setBuffersDataSpace(g_outputWindow, kAdataspaceSrgb);
        g_outputWindowConfiguredW = srcW;
        g_outputWindowConfiguredH = srcH;
        LOGI("stage3.3c: configured output window %dx%d (src stride=%d px)",
             srcW, srcH, srcStridePx);
    }

    void* srcPtr = nullptr;
    const int lockResult = AHardwareBuffer_lock(
        srcAhb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &srcPtr);
    if (lockResult != 0 || srcPtr == nullptr) {
        LOGE("stage3.3c: AHardwareBuffer_lock(%s) rc=%d ptr=%p", tag, lockResult, srcPtr);
        return;
    }

    ANativeWindow_Buffer dst{};
    const int32_t rc = ANativeWindow_lock(g_outputWindow, &dst, nullptr);
    if (rc != 0) {
        LOGE("stage3.3c: ANativeWindow_lock(%s) rc=%d", tag, rc);
        AHardwareBuffer_unlock(srcAhb, nullptr);
        return;
    }

    const int32_t copyW = std::min(srcW, dst.width);
    const int32_t copyH = std::min(srcH, dst.height);
    const uint8_t* srcRow = static_cast<const uint8_t*>(srcPtr);
    uint8_t* dstRow = static_cast<uint8_t*>(dst.bits);
    const size_t srcRowBytes = static_cast<size_t>(srcStridePx) * 4u;
    const size_t dstRowBytes = static_cast<size_t>(dst.stride) * 4u;
    const size_t copyRowBytes = static_cast<size_t>(copyW) * 4u;
    for (int32_t y = 0; y < copyH; ++y) {
        std::memcpy(dstRow, srcRow, copyRowBytes);
        srcRow += srcRowBytes;
        dstRow += dstRowBytes;
    }

    ANativeWindow_unlockAndPost(g_outputWindow);
    AHardwareBuffer_unlock(srcAhb, nullptr);

    g_blitCount += 1;
    if (g_blitCount == 1 || (g_blitCount % 120) == 0) {
        LOGI("stage3.3c: blit ok tag=%s count=%llu src=%dx%d/stride=%d dst=%dx%d/stride=%d",
             tag,
             static_cast<unsigned long long>(g_blitCount),
             srcW, srcH, srcStridePx, dst.width, dst.height, dst.stride);
    }
}

// 阶段 3.3a-iii.b.2：对当前 decoder VkImage + view 提交一次 YUV→RGBA compute dispatch，
// 写到 ping-pong input{0,1}Image。调用方必须持 g_contextMutex，且 g_vk / g_context / pipeline 必备。
// decoder image 假设 AHB queue family 为 FOREIGN_EXT；本函数做 acquire barrier 接管。
// 返回 false 表示本帧 dispatch 失败（不影响后续）。
bool dispatchYuvToRgbaLocked(VkImage decoderImage, VkImageView decoderView) {
    if (g_context == nullptr || g_vk == nullptr) {
        return false;
    }
    if (g_context->pipeline == VK_NULL_HANDLE || g_context->dsSets[0] == VK_NULL_HANDLE) {
        return false;
    }
    VkDevice device = g_vk->device;

    const uint32_t slot = g_context->pingPongIndex & 1U;
    g_context->pingPongIndex = (g_context->pingPongIndex + 1U) & 1U;
    const VkImage inputImage = (slot == 0) ? g_context->input0Image : g_context->input1Image;

    // 1. 用本帧 decoder view 覆写 binding 0（immutable sampler 由 layout 提供，sampler 字段忽略）。
    VkDescriptorImageInfo srcInfo{
        .sampler = VK_NULL_HANDLE,
        .imageView = decoderView,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkWriteDescriptorSet writeSrc{
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .pNext = nullptr,
        .dstSet = g_context->dsSets[slot],
        .dstBinding = 0,
        .dstArrayElement = 0,
        .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        .pImageInfo = &srcInfo,
        .pBufferInfo = nullptr,
        .pTexelBufferView = nullptr,
    };
    vkUpdateDescriptorSets(device, 1, &writeSrc, 0, nullptr);

    // 2. 一次性 cmd buffer + fence（60 帧节奏，性能可忽略；后续 3.3b 再改成池化）。
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo cmdAi{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .pNext = nullptr,
        .commandPool = g_vk->cmdPool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    if (vkAllocateCommandBuffers(device, &cmdAi, &cmd) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.2: vkAllocateCommandBuffers failed");
        return false;
    }

    VkCommandBufferBeginInfo cmdBi{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
        .pInheritanceInfo = nullptr,
    };
    vkBeginCommandBuffer(cmd, &cmdBi);

    // 3. 双 barrier：
    //  - decoder image: FOREIGN_EXT → 我方 queue family，UNDEFINED → SHADER_READ_ONLY_OPTIMAL；
    //  - input image:  IGNORED → IGNORED，UNDEFINED → GENERAL（旧内容不保留）。
    VkImageMemoryBarrier barriers[2]{};
    barriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[0].srcAccessMask = 0;
    barriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    barriers[0].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barriers[0].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    barriers[0].dstQueueFamilyIndex = g_vk->queueFamilyIndex;
    barriers[0].image = decoderImage;
    barriers[0].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    barriers[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[1].srcAccessMask = 0;
    barriers[1].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barriers[1].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barriers[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[1].image = inputImage;
    barriers[1].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0,
                         0, nullptr,
                         0, nullptr,
                         2, barriers);

    // 4. dispatch。
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_context->pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_context->pipelineLayout, 0, 1, &g_context->dsSets[slot],
                            0, nullptr);
    const uint32_t gx = (g_context->width + 7U) / 8U;
    const uint32_t gy = (g_context->height + 7U) / 8U;
    vkCmdDispatch(cmd, gx, gy, 1);

    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.2: vkEndCommandBuffer failed");
        vkFreeCommandBuffers(device, g_vk->cmdPool, 1, &cmd);
        return false;
    }

    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fci{
        .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
    };
    if (vkCreateFence(device, &fci, nullptr, &fence) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.2: vkCreateFence failed");
        vkFreeCommandBuffers(device, g_vk->cmdPool, 1, &cmd);
        return false;
    }

    VkSubmitInfo si{
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .pNext = nullptr,
        .waitSemaphoreCount = 0,
        .pWaitSemaphores = nullptr,
        .pWaitDstStageMask = nullptr,
        .commandBufferCount = 1,
        .pCommandBuffers = &cmd,
        .signalSemaphoreCount = 0,
        .pSignalSemaphores = nullptr,
    };
    VkResult sres = vkQueueSubmit(g_vk->queue, 1, &si, fence);
    if (sres != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.2: vkQueueSubmit failed res=%d", sres);
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, g_vk->cmdPool, 1, &cmd);
        return false;
    }

    // 1s 上限，足够覆盖 4K dispatch；超时算失败。
    VkResult wres = vkWaitForFences(device, 1, &fence, VK_TRUE, 1'000'000'000ULL);
    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, g_vk->cmdPool, 1, &cmd);
    if (wres != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.2: vkWaitForFences res=%d", wres);
        return false;
    }

    g_context->dispatchCount += 1;
    if (g_context->dispatchCount == 1 || (g_context->dispatchCount % 60) == 0) {
        LOGI("stage3.3a-iii.b.2: dispatch ok slot=%u count=%llu groups=%ux%u extent=%ux%u",
             slot,
             static_cast<unsigned long long>(g_context->dispatchCount),
             gx, gy, g_context->width, g_context->height);
    }

    // 阶段 3.3b：input AHB 内容已就绪，请求 LSFG 用 input0/input1 生成中间帧到 output AHB。
    // LSFG 内部按调用次数 frameIdx % 2 选 input slot，与本侧 pingPongIndex 对齐。
    // 第一次调用时另一 slot 还没有数据，生成结果是 garbage —— output 还没回贴 SurfaceView，
    // 视觉无副作用；只要不报 vulkan error / 不 crash 就算通过。
    try {
        std::vector<int> noOutSems;
        LSFG_3_1::presentContext(g_context->contextId, -1, noOutSems);
        LSFG_3_1::waitIdle();
        if (g_context->dispatchCount == 1 || (g_context->dispatchCount % 60) == 0) {
            LOGI("stage3.3b: LSFG presentContext+waitIdle ok ctx=%d slot=%u",
                 g_context->contextId, slot);
        }
    } catch (const std::exception& e) {
        LOGE("stage3.3b: LSFG presentContext threw: %s", e.what());
        return false;
    }

    // 阶段 3.3c (优化)：标准 2x 插帧顺序 —— 先贴 LSFG 插值帧（位于 realN-1 与 realN 之间），
    // 再贴 realN 本帧。两次 ANativeWindow_unlockAndPost 会被 SurfaceFlinger 按顺序入队，
    // 在 120Hz 显示器上可达成真 2x 视觉效果，去除"只贴插值帧"导致的液化。
    // 第一次调用时另一 input slot 还没有真实内容，插值帧是 garbage，但仅持续一帧。
    AHardwareBuffer* realAhb = (slot == 0) ? g_context->input0.get() : g_context->input1.get();
    blitAhbToWindowLocked(g_context->outputs[0].get(), "interp");
    blitAhbToWindowLocked(realAhb, "real");
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
    // 节流：每帧都进来，但日志只在第 1 次和每 60 次打印一次。
    static uint64_t s_importCount = 0;
    ++s_importCount;
    const bool logImport = (s_importCount == 1) || (s_importCount % 60 == 0);

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
    const bool pipelineReady = ensureYcbcrPipelineLocked(formatProps);

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

    if (logImport) {
        LOGI("stage3.3a-ii: decoder AHB import ok externalFormat=0x%llx size=%llu memBits=0x%x "
             "samplerYcbcrFeatures=0x%x",
             static_cast<unsigned long long>(formatProps.externalFormat),
             static_cast<unsigned long long>(ahbProps.allocationSize),
             ahbProps.memoryTypeBits,
             formatProps.formatFeatures);
    }

    // 阶段 3.3a-iii.b.2：建立 decoder image view（绑 ycbcr conversion）+ 提交一次 YUV→RGBA dispatch。
    bool dispatchOk = false;
    if (pipelineReady && g_context != nullptr && g_context->pipeline != VK_NULL_HANDLE) {
        VkSamplerYcbcrConversionInfo cvtInfoView{
            .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
            .pNext = nullptr,
            .conversion = g_context->ycbcrConversion,
        };
        VkImageViewCreateInfo viewCi{
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .pNext = &cvtInfoView,
            .flags = 0,
            .image = image,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = VK_FORMAT_UNDEFINED,
            .components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                           VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY},
            .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        VkImageView decoderView = VK_NULL_HANDLE;
        VkResult vres = vkCreateImageView(g_vk->device, &viewCi, nullptr, &decoderView);
        if (vres != VK_SUCCESS) {
            LOGE("stage3.3a-iii.b.2: vkCreateImageView (decoder ycbcr) failed res=%d", vres);
        } else {
            dispatchOk = dispatchYuvToRgbaLocked(image, decoderView);
            vkDestroyImageView(g_vk->device, decoderView, nullptr);
        }
    }

    vkFreeMemory(g_vk->device, memory, nullptr);
    vkDestroyImage(g_vk->device, image, nullptr);
    return dispatchOk || !pipelineReady;
}

} // namespace FramegenPipeline
