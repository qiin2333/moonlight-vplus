#include "framegen_pipeline.hpp"

#include <android/log.h>
#include <volk.h>
#include <vulkan/vulkan_core.h>

#include <lsfg_3_1.hpp>

#include "extract/extract.hpp"
#include "extract/trans.hpp"

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
