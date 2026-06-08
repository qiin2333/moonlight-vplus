#include "framegen_pipeline.hpp"

#include <android/log.h>
#include <volk.h>
#include <vulkan/vulkan_core.h>

#include <lsfg_3_1.hpp>

#include "extract/extract.hpp"
#include "extract/trans.hpp"

#include "yuv_to_rgba.comp.spv.h"
#include "rgba_upscale.comp.spv.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <deque>
#include <dlfcn.h>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
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

struct DecoderAhbImport {
    AhbPtr ahb;
    AHardwareBuffer* key{nullptr};
    VkImage image{VK_NULL_HANDLE};
    VkDeviceMemory memory{VK_NULL_HANDLE};
    VkImageView view{VK_NULL_HANDLE};
    uint32_t width{0};
    uint32_t height{0};
    uint32_t stride{0};
    uint64_t externalFormat{0};
    uint64_t allocationSize{0};
    uint32_t memoryTypeBits{0};
    uint64_t lastUse{0};
    bool layoutInitialized{false};
};

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
    AhbPtr realOutput;
    AhbPtr interpOutput;
    std::vector<AhbPtr> outputs;
    int32_t contextId{-1};
    uint32_t width{0};
    uint32_t height{0};
    uint32_t presentWidth{0};
    uint32_t presentHeight{0};
    float srcUvScaleX{1.0F};
    float srcUvScaleY{1.0F};

    // 阶段 3.3a-iii.a：owned input AHB import 后的 VkImage / memory / view。
    // 这些资源跟 AHB 绑定，整个 framegen 生命周期内不变。
    VkDevice ownerDevice{VK_NULL_HANDLE};
    VkCommandPool ownerCmdPool{VK_NULL_HANDLE};
    VkImage input0Image{VK_NULL_HANDLE};
    VkDeviceMemory input0Memory{VK_NULL_HANDLE};
    VkImageView input0View{VK_NULL_HANDLE};
    VkImage input1Image{VK_NULL_HANDLE};
    VkDeviceMemory input1Memory{VK_NULL_HANDLE};
    VkImageView input1View{VK_NULL_HANDLE};
    VkImage realOutputImage{VK_NULL_HANDLE};
    VkDeviceMemory realOutputMemory{VK_NULL_HANDLE};
    VkImageView realOutputView{VK_NULL_HANDLE};
    VkImage interpOutputImage{VK_NULL_HANDLE};
    VkDeviceMemory interpOutputMemory{VK_NULL_HANDLE};
    VkImageView interpOutputView{VK_NULL_HANDLE};
    VkImage lsfgOutputImage{VK_NULL_HANDLE};
    VkDeviceMemory lsfgOutputMemory{VK_NULL_HANDLE};
    VkImageView lsfgOutputView{VK_NULL_HANDLE};

    // 阶段 3.3a-iii.a：YUV→RGBA compute pipeline（不含 ycbcr conversion；
    // conversion 与 decoder external format 绑定，要等首帧到达后才在 3.3a-iii.b 创建）。
    VkShaderModule shaderModule{VK_NULL_HANDLE};
    VkShaderModule upscaleShaderModule{VK_NULL_HANDLE};

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
    VkDescriptorSet dsSets[3]{VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkSampler rgbaSampler{VK_NULL_HANDLE};
    VkDescriptorSetLayout upscaleDsLayout{VK_NULL_HANDLE};
    VkPipelineLayout upscalePipelineLayout{VK_NULL_HANDLE};
    VkPipeline upscalePipeline{VK_NULL_HANDLE};
    VkDescriptorPool upscaleDsPool{VK_NULL_HANDLE};
    VkDescriptorSet upscaleDsSet{VK_NULL_HANDLE};
    VkCommandBuffer upscaleCmd{VK_NULL_HANDLE};
    VkFence upscaleFence{VK_NULL_HANDLE};
    uint32_t pingPongIndex{0};
    uint64_t dispatchCount{0};
    uint64_t lsfgPresentCount{0};
    bool inputInitialized[2]{false, false};
    VkCommandBuffer dispatchCmd{VK_NULL_HANDLE};
    VkFence dispatchFence{VK_NULL_HANDLE};
    std::vector<DecoderAhbImport> decoderImports;

    ~ContextResources() {
        if (ownerDevice == VK_NULL_HANDLE) {
            return;
        }
        for (auto& import : decoderImports) {
            if (import.view != VK_NULL_HANDLE) {
                vkDestroyImageView(ownerDevice, import.view, nullptr);
            }
            if (import.image != VK_NULL_HANDLE) {
                vkDestroyImage(ownerDevice, import.image, nullptr);
            }
            if (import.memory != VK_NULL_HANDLE) {
                vkFreeMemory(ownerDevice, import.memory, nullptr);
            }
        }
        decoderImports.clear();
        if (dispatchFence != VK_NULL_HANDLE) {
            vkDestroyFence(ownerDevice, dispatchFence, nullptr);
        }
        if (upscaleFence != VK_NULL_HANDLE) {
            vkDestroyFence(ownerDevice, upscaleFence, nullptr);
        }
        if (dispatchCmd != VK_NULL_HANDLE && ownerCmdPool != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(ownerDevice, ownerCmdPool, 1, &dispatchCmd);
        }
        if (upscaleCmd != VK_NULL_HANDLE && ownerCmdPool != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(ownerDevice, ownerCmdPool, 1, &upscaleCmd);
        }
        if (dsPool != VK_NULL_HANDLE) {
            // descriptor sets 自动随 pool 销毁。
            vkDestroyDescriptorPool(ownerDevice, dsPool, nullptr);
        }
        if (upscaleDsPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(ownerDevice, upscaleDsPool, nullptr);
        }
        if (pipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(ownerDevice, pipeline, nullptr);
        }
        if (upscalePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(ownerDevice, upscalePipeline, nullptr);
        }
        if (pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(ownerDevice, pipelineLayout, nullptr);
        }
        if (upscalePipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(ownerDevice, upscalePipelineLayout, nullptr);
        }
        if (dsLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(ownerDevice, dsLayout, nullptr);
        }
        if (upscaleDsLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(ownerDevice, upscaleDsLayout, nullptr);
        }
        if (ycbcrSampler != VK_NULL_HANDLE) {
            vkDestroySampler(ownerDevice, ycbcrSampler, nullptr);
        }
        if (rgbaSampler != VK_NULL_HANDLE) {
            vkDestroySampler(ownerDevice, rgbaSampler, nullptr);
        }
        if (ycbcrConversion != VK_NULL_HANDLE) {
            vkDestroySamplerYcbcrConversion(ownerDevice, ycbcrConversion, nullptr);
        }
        if (shaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(ownerDevice, shaderModule, nullptr);
        }
        if (upscaleShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(ownerDevice, upscaleShaderModule, nullptr);
        }
        if (input0View != VK_NULL_HANDLE) {
            vkDestroyImageView(ownerDevice, input0View, nullptr);
        }
        if (input1View != VK_NULL_HANDLE) {
            vkDestroyImageView(ownerDevice, input1View, nullptr);
        }
        if (realOutputView != VK_NULL_HANDLE) {
            vkDestroyImageView(ownerDevice, realOutputView, nullptr);
        }
        if (interpOutputView != VK_NULL_HANDLE) {
            vkDestroyImageView(ownerDevice, interpOutputView, nullptr);
        }
        if (lsfgOutputView != VK_NULL_HANDLE) {
            vkDestroyImageView(ownerDevice, lsfgOutputView, nullptr);
        }
        if (input0Image != VK_NULL_HANDLE) {
            vkDestroyImage(ownerDevice, input0Image, nullptr);
        }
        if (input1Image != VK_NULL_HANDLE) {
            vkDestroyImage(ownerDevice, input1Image, nullptr);
        }
        if (realOutputImage != VK_NULL_HANDLE) {
            vkDestroyImage(ownerDevice, realOutputImage, nullptr);
        }
        if (interpOutputImage != VK_NULL_HANDLE) {
            vkDestroyImage(ownerDevice, interpOutputImage, nullptr);
        }
        if (lsfgOutputImage != VK_NULL_HANDLE) {
            vkDestroyImage(ownerDevice, lsfgOutputImage, nullptr);
        }
        if (input0Memory != VK_NULL_HANDLE) {
            vkFreeMemory(ownerDevice, input0Memory, nullptr);
        }
        if (input1Memory != VK_NULL_HANDLE) {
            vkFreeMemory(ownerDevice, input1Memory, nullptr);
        }
        if (realOutputMemory != VK_NULL_HANDLE) {
            vkFreeMemory(ownerDevice, realOutputMemory, nullptr);
        }
        if (interpOutputMemory != VK_NULL_HANDLE) {
            vkFreeMemory(ownerDevice, interpOutputMemory, nullptr);
        }
        if (lsfgOutputMemory != VK_NULL_HANDLE) {
            vkFreeMemory(ownerDevice, lsfgOutputMemory, nullptr);
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
int32_t g_outputWindowHintedFps = 0;
bool g_outputWindowBuffersRequested = false;
std::atomic<uint64_t> g_blitCount{0};

struct PresentFrame {
    ANativeWindow* window{nullptr};
    std::vector<uint8_t> rgba;
    int32_t width{0};
    int32_t height{0};
    int32_t displayWidth{0};
    int32_t displayHeight{0};
    uint64_t sourceFrame{0};
    uint32_t sourceOrder{0};
    std::string tag;
};

std::mutex g_presentMutex;
std::condition_variable g_presentCv;
std::deque<PresentFrame> g_presentQueue;
std::thread g_presentThread;
bool g_presentStop = false;
uint64_t g_presentEnqueued = 0;
uint64_t g_presentDropped = 0;
std::atomic<int32_t> g_presentQueueMax{2};

struct AdaptiveFramegenState {
    int64_t lastTimestampNs{0};
    double inputFpsEma{0.0};
    bool highInputBypass{false};
    uint32_t highInputFrames{0};
    uint32_t lowInputFrames{0};
    uint32_t slowLsfgFrames{0};
    uint32_t slowCooldownFrames{0};
    uint32_t cadenceBreakFrames{0};
    uint32_t cadenceSuppressFrames{0};
};

struct YuvToRgbaPushConstants {
    float srcUvScaleX{1.0F};
    float srcUvScaleY{1.0F};
};

std::atomic<int32_t> g_outputFrameRate{0};
std::atomic<int32_t> g_internalFramegenWidth{864};
std::atomic<int32_t> g_presentMode{0};
std::atomic<int32_t> g_slowLsfgThresholdMs{18};
AdaptiveFramegenState g_adaptive;

constexpr uint64_t kFirstAvailableDeviceUuid = 0x1463ABACULL;
constexpr uint32_t kGenerationCount = 1; // 2x 插帧：每两帧之间生成 1 帧。
constexpr int64_t kSlowFrameMs = 16;
constexpr int64_t kSlowBlitMs = 8;
constexpr uint32_t kCadenceRecoverySuppressFrames = 2;

int64_t elapsedMs(std::chrono::steady_clock::time_point start,
                  std::chrono::steady_clock::time_point end) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
}

void applyOutputWindowHintsLocked(bool requestBuffers) {
    if (g_outputWindow == nullptr) {
        return;
    }

    using SetFrameRateWithChangeStrategyFn =
        int32_t (*)(ANativeWindow*, float, int8_t, int8_t);
    static auto setFrameRateWithChangeStrategy =
        reinterpret_cast<SetFrameRateWithChangeStrategyFn>(
            dlsym(RTLD_DEFAULT, "ANativeWindow_setFrameRateWithChangeStrategy"));

    const int32_t targetFps = g_outputFrameRate.load(std::memory_order_acquire);
    if (targetFps > 0 && targetFps != g_outputWindowHintedFps &&
        setFrameRateWithChangeStrategy != nullptr) {
        constexpr int8_t kFrameRateCompatibilityDefault = 0;
        constexpr int8_t kChangeFrameRateAlways = 1;
        const int32_t rc = setFrameRateWithChangeStrategy(
            g_outputWindow,
            static_cast<float>(targetFps),
            kFrameRateCompatibilityDefault,
            kChangeFrameRateAlways);
        LOGI("stage3.3c: native window frame-rate hint fps=%d rc=%d",
             targetFps, rc);
        if (rc == 0) {
            g_outputWindowHintedFps = targetFps;
        }
    }

    if (!requestBuffers || g_outputWindowBuffersRequested) {
        return;
    }
    using TryAllocateBuffersFn = void (*)(ANativeWindow*);
    static auto tryAllocateBuffers =
        reinterpret_cast<TryAllocateBuffersFn>(
            dlsym(RTLD_DEFAULT, "ANativeWindow_tryAllocateBuffers"));
    if (tryAllocateBuffers != nullptr) {
        tryAllocateBuffers(g_outputWindow);
        LOGI("stage3.3c: native window tryAllocateBuffers requested");
    }
    g_outputWindowBuffersRequested = true;
}

void releasePresentFrame(PresentFrame& frame) {
    if (frame.window != nullptr) {
        ANativeWindow_release(frame.window);
        frame.window = nullptr;
    }
}

uint64_t clearPresenterQueue(const char* reason) {
    uint64_t cleared = 0;
    {
        std::lock_guard<std::mutex> lock(g_presentMutex);
        cleared = static_cast<uint64_t>(g_presentQueue.size());
        for (auto& frame : g_presentQueue) {
            releasePresentFrame(frame);
        }
        g_presentQueue.clear();
        g_presentDropped += cleared;
    }
    if (cleared > 0) {
        LOGW("stage3.3c: presenter queue clear reason=%s cleared=%llu",
             reason != nullptr ? reason : "",
             static_cast<unsigned long long>(cleared));
    }
    return cleared;
}

void copyRgbaToWindowBuffer(const PresentFrame& frame, ANativeWindow_Buffer& dst) {
    const int32_t dstW = std::min(dst.width, frame.displayWidth > 0 ? frame.displayWidth : frame.width);
    const int32_t dstH = std::min(dst.height, frame.displayHeight > 0 ? frame.displayHeight : frame.height);
    if (dstW <= 0 || dstH <= 0 || frame.width <= 0 || frame.height <= 0) {
        return;
    }

    const uint8_t* src = frame.rgba.data();
    uint8_t* dstBase = static_cast<uint8_t*>(dst.bits);
    const size_t srcRowBytes = static_cast<size_t>(frame.width) * 4u;
    const size_t dstRowBytes = static_cast<size_t>(dst.stride) * 4u;

    if (frame.width == dstW && frame.height == dstH) {
        const size_t copyRowBytes = static_cast<size_t>(dstW) * 4u;
        const uint8_t* srcRow = src;
        uint8_t* dstRow = dstBase;
        for (int32_t y = 0; y < dstH; ++y) {
            std::memcpy(dstRow, srcRow, copyRowBytes);
            srcRow += srcRowBytes;
            dstRow += dstRowBytes;
        }
        return;
    }

    const auto* srcPixels = reinterpret_cast<const uint32_t*>(src);
    const int64_t xStep = (static_cast<int64_t>(frame.width) << 32) / dstW;
    const int64_t yStep = (static_cast<int64_t>(frame.height) << 32) / dstH;

    for (int32_t y = 0; y < dstH; ++y) {
        const int32_t sy = std::min(static_cast<int32_t>((yStep * y) >> 32), frame.height - 1);
        const uint32_t* srcRow = srcPixels + static_cast<size_t>(sy) * frame.width;
        auto* dstRow = reinterpret_cast<uint32_t*>(dstBase + static_cast<size_t>(y) * dstRowBytes);

        int64_t sxAcc = 0;
        for (int32_t x = 0; x < dstW; ++x) {
            const int32_t sx = std::min(static_cast<int32_t>(sxAcc >> 32), frame.width - 1);
            dstRow[x] = srcRow[sx];
            sxAcc += xStep;
        }
    }
}

void presentFrameToWindow(PresentFrame frame) {
    if (frame.window == nullptr || frame.rgba.empty() || frame.width <= 0 || frame.height <= 0) {
        releasePresentFrame(frame);
        return;
    }

    const auto totalStart = std::chrono::steady_clock::now();
    ANativeWindow_Buffer dst{};
    const auto windowLockStart = std::chrono::steady_clock::now();
    const int32_t rc = ANativeWindow_lock(frame.window, &dst, nullptr);
    const auto windowLockEnd = std::chrono::steady_clock::now();
    if (rc != 0) {
        LOGE("stage3.3c: presenter ANativeWindow_lock(%s) rc=%d",
             frame.tag.c_str(), rc);
        releasePresentFrame(frame);
        return;
    }

    const auto copyStart = std::chrono::steady_clock::now();
    copyRgbaToWindowBuffer(frame, dst);
    const auto copyEnd = std::chrono::steady_clock::now();

    const auto postStart = std::chrono::steady_clock::now();
    const int32_t postRc = ANativeWindow_unlockAndPost(frame.window);
    const auto postEnd = std::chrono::steady_clock::now();
    if (postRc != 0) {
        LOGE("stage3.3c: presenter ANativeWindow_unlockAndPost(%s) rc=%d",
             frame.tag.c_str(), postRc);
    }
    releasePresentFrame(frame);
    const auto totalEnd = std::chrono::steady_clock::now();

    const uint64_t blitCount = g_blitCount.fetch_add(1, std::memory_order_relaxed) + 1;
    const int64_t totalMs = elapsedMs(totalStart, totalEnd);
    const bool logSample = (blitCount == 1 || (blitCount % 120) == 0);
    if (logSample || totalMs >= kSlowBlitMs) {
        LOGI("stage3.3c: presenter timing tag=%s count=%llu src=%llu/%u total=%lldms "
             "winLock=%lldms copy=%lldms post=%lldms src=%dx%d display=%dx%d dst=%dx%d/stride=%d",
             frame.tag.c_str(),
             static_cast<unsigned long long>(blitCount),
             static_cast<unsigned long long>(frame.sourceFrame),
             frame.sourceOrder,
             static_cast<long long>(totalMs),
             static_cast<long long>(elapsedMs(windowLockStart, windowLockEnd)),
             static_cast<long long>(elapsedMs(copyStart, copyEnd)),
             static_cast<long long>(elapsedMs(postStart, postEnd)),
             frame.width, frame.height,
             frame.displayWidth, frame.displayHeight,
             dst.width, dst.height, dst.stride);
    }
}

void presenterLoop() {
    for (;;) {
        PresentFrame frame;
        {
            std::unique_lock<std::mutex> lock(g_presentMutex);
            g_presentCv.wait(lock, [] {
                return g_presentStop || !g_presentQueue.empty();
            });
            if (g_presentStop && g_presentQueue.empty()) {
                break;
            }
            frame = std::move(g_presentQueue.front());
            g_presentQueue.pop_front();
        }
        presentFrameToWindow(std::move(frame));
    }
}

void ensurePresenterThreadLocked() {
    std::lock_guard<std::mutex> lock(g_presentMutex);
    if (!g_presentThread.joinable()) {
        g_presentStop = false;
        g_presentThread = std::thread(presenterLoop);
        LOGI("stage3.3c: presenter thread started");
    }
}

void stopPresenterThread() {
    std::thread presenter;
    {
        std::lock_guard<std::mutex> lock(g_presentMutex);
        g_presentStop = true;
        for (auto& frame : g_presentQueue) {
            releasePresentFrame(frame);
        }
        g_presentQueue.clear();
        presenter = std::move(g_presentThread);
    }
    g_presentCv.notify_all();
    if (presenter.joinable()) {
        presenter.join();
        LOGI("stage3.3c: presenter thread stopped");
    }
    {
        std::lock_guard<std::mutex> lock(g_presentMutex);
        g_presentStop = false;
        g_presentEnqueued = 0;
        g_presentDropped = 0;
    }
}

uint32_t alignDown(uint32_t value, uint32_t alignment) {
    if (alignment == 0) {
        return value;
    }
    return value - (value % alignment);
}

VkExtent2D chooseFramegenExtent(uint32_t streamWidth,
                                uint32_t streamHeight,
                                uint32_t decoderWidth,
                                uint32_t decoderHeight) {
    VkExtent2D extent{
        decoderWidth != 0 ? decoderWidth : streamWidth,
        decoderHeight != 0 ? decoderHeight : streamHeight,
    };

    const int32_t targetFps = g_outputFrameRate.load(std::memory_order_acquire);
    if (targetFps < 100 || streamWidth == 0 || streamHeight == 0) {
        return extent;
    }

    // 60->120 is currently bound by LSFG shader cost, not by our YUV dispatch.
    // Keep decode/presentation full-size, but feed LSFG a configurable internal
    // surface. The final real/interpolated frames are written back at stream size.
    constexpr uint32_t kExtentAlignment = 2;
    const int32_t configured = g_internalFramegenWidth.load(std::memory_order_acquire);
    const uint32_t configuredWidth = static_cast<uint32_t>(
        std::clamp(configured > 0 ? configured : 864, 320, 1920));
    const uint32_t internalWidth = alignDown(configuredWidth, kExtentAlignment);
    if (internalWidth >= kExtentAlignment &&
        streamWidth > internalWidth) {
        const uint32_t scaledHeight = static_cast<uint32_t>(
            (static_cast<uint64_t>(streamHeight) * internalWidth +
             (static_cast<uint64_t>(streamWidth) / 2U)) /
            streamWidth);
        extent.width = internalWidth;
        extent.height = std::max(kExtentAlignment,
                                 alignDown(scaledHeight, kExtentAlignment));
    }

    return extent;
}

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
        const uint32_t streamWidth = width > 0
            ? static_cast<uint32_t>(width)
            : decoderDesc.width;
        const uint32_t streamHeight = height > 0
            ? static_cast<uint32_t>(height)
            : decoderDesc.height;
        const VkExtent2D fgExtent = chooseFramegenExtent(
            streamWidth,
            streamHeight,
            decoderDesc.width,
            decoderDesc.height);
        const uint32_t ctxWidth = fgExtent.width;
        const uint32_t ctxHeight = fgExtent.height;
        const auto bootStart = std::chrono::steady_clock::now();

        LOGI("stage3.2 bootstrap start: owned RGBA AHB context %ux%u stream=%ux%u decoder=%ux%u fmt=0x%x usage=0x%llx target=%d internalWidth=%d",
             ctxWidth, ctxHeight,
             streamWidth, streamHeight,
             decoderDesc.width, decoderDesc.height,
             decoderDesc.format,
             static_cast<unsigned long long>(decoderDesc.usage),
             g_outputFrameRate.load(std::memory_order_acquire),
             g_internalFramegenWidth.load(std::memory_order_acquire));

        if (g_vk == nullptr) {
            const auto t0 = std::chrono::steady_clock::now();
            g_vk = buildVulkanContext();
            const auto t1 = std::chrono::steady_clock::now();
            LOGI("stage3.2 bootstrap timing: buildVulkan=%lldms total=%lldms",
                 static_cast<long long>(elapsedMs(t0, t1)),
                 static_cast<long long>(elapsedMs(bootStart, t1)));
        } else {
            LOGI("stage3.2 bootstrap timing: buildVulkan=reuse total=%lldms",
                 static_cast<long long>(elapsedMs(bootStart, std::chrono::steady_clock::now())));
        }

        const auto extractStart = std::chrono::steady_clock::now();
        Extract::extractShaders();
        const auto extractEnd = std::chrono::steady_clock::now();
        LOGI("stage3.2 bootstrap timing: extractShaders=%lldms total=%lldms",
             static_cast<long long>(elapsedMs(extractStart, extractEnd)),
             static_cast<long long>(elapsedMs(bootStart, extractEnd)));

        // 先创建 LSFG 自己的 AHB 共享上下文。注意：这里还没有把 decoder AHB copy 到 owned input，
        // 所以后续 submit 仍保持关闭；这一步只验证 device/shader/context 三件事能否真正建立。
        auto resources = std::make_unique<ContextResources>();
        resources->width = ctxWidth;
        resources->height = ctxHeight;
        resources->presentWidth = streamWidth;
        resources->presentHeight = streamHeight;
        if (decoderDesc.width > 0 && streamWidth > 0) {
            resources->srcUvScaleX = std::min(
                1.0F,
                static_cast<float>(streamWidth) / static_cast<float>(decoderDesc.width));
        }
        if (decoderDesc.height > 0 && streamHeight > 0) {
            resources->srcUvScaleY = std::min(
                1.0F,
                static_cast<float>(streamHeight) / static_cast<float>(decoderDesc.height));
        }
        const auto allocateStart = std::chrono::steady_clock::now();
        resources->input0 = allocateOwnedRgbaAhb(ctxWidth, ctxHeight);
        resources->input1 = allocateOwnedRgbaAhb(ctxWidth, ctxHeight);
        resources->realOutput = allocateOwnedRgbaAhb(resources->presentWidth, resources->presentHeight);
        resources->interpOutput = allocateOwnedRgbaAhb(resources->presentWidth, resources->presentHeight);
        resources->outputs.emplace_back(allocateOwnedRgbaAhb(ctxWidth, ctxHeight));
        const auto allocateEnd = std::chrono::steady_clock::now();
        LOGI("stage3.2 bootstrap timing: allocateOwnedAhb=%lldms total=%lldms fg=%ux%u present=%ux%u",
             static_cast<long long>(elapsedMs(allocateStart, allocateEnd)),
             static_cast<long long>(elapsedMs(bootStart, allocateEnd)),
             ctxWidth, ctxHeight,
             resources->presentWidth, resources->presentHeight);

        std::vector<AHardwareBuffer*> outputAhbs;
        outputAhbs.reserve(resources->outputs.size());
        for (const auto& output : resources->outputs) {
            outputAhbs.push_back(output.get());
        }

        setenv("DISABLE_LSFG", "1", 1); // NOLINT(concurrency-mt-unsafe)
        const bool hdrEnabled = g_hdrEnabled.load(std::memory_order_acquire);
        constexpr float kFlowScale = 1.0F; // 实测：Adreno 上 <1.0 反而显著变慢（0.5 → waitIdle 65ms→245ms）
        const auto lsfgInitStart = std::chrono::steady_clock::now();
        LSFG_3_1::initialize(
            kFirstAvailableDeviceUuid,
            hdrEnabled,
            kFlowScale,
            kGenerationCount,
            loadTranslatedShader);
        const auto lsfgInitEnd = std::chrono::steady_clock::now();
        LOGI("stage3.2 bootstrap: LSFG_3_1::initialize isHdr=%d flowScale=%.2f generationCount=%u",
             static_cast<int>(hdrEnabled), kFlowScale, kGenerationCount);
        LOGI("stage3.2 bootstrap timing: lsfgInitialize=%lldms total=%lldms",
             static_cast<long long>(elapsedMs(lsfgInitStart, lsfgInitEnd)),
             static_cast<long long>(elapsedMs(bootStart, lsfgInitEnd)));

        const auto createContextStart = std::chrono::steady_clock::now();
        resources->contextId = LSFG_3_1::createContextFromAHB(
            resources->input0.get(),
            resources->input1.get(),
            outputAhbs,
            VkExtent2D{ctxWidth, ctxHeight},
            VK_FORMAT_R8G8B8A8_UNORM);
        const auto createContextEnd = std::chrono::steady_clock::now();
        LOGI("stage3.2 bootstrap timing: createContextFromAHB=%lldms total=%lldms context=%d",
             static_cast<long long>(elapsedMs(createContextStart, createContextEnd)),
             static_cast<long long>(elapsedMs(bootStart, createContextEnd)),
             resources->contextId);
        unsetenv("DISABLE_LSFG"); // NOLINT(concurrency-mt-unsafe)

        // 阶段 3.3a-iii.a：把 owned input0/input1 RGBA AHB 同步 import 到我们自己的 VkDevice，
        // 这样后面 compute shader 可以把 YUV decoder 帧解码写到这些 VkImage 上，LSFG 通过 AHB
        // 共享自动看到内容。output AHB 暂不在我们这边 import，由 LSFG 内部 device 持有。
        resources->ownerDevice = g_vk->device;
        resources->ownerCmdPool = g_vk->cmdPool;
        const auto ownedImportStart = std::chrono::steady_clock::now();
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
            auto real = importOwnedRgbaAhb(g_vk->device, g_vk->memProps,
                                           resources->realOutput.get(),
                                           resources->presentWidth,
                                           resources->presentHeight);
            resources->realOutputImage = real.image;
            resources->realOutputMemory = real.memory;
            resources->realOutputView = real.view;
            auto interp = importOwnedRgbaAhb(g_vk->device, g_vk->memProps,
                                             resources->interpOutput.get(),
                                             resources->presentWidth,
                                             resources->presentHeight);
            resources->interpOutputImage = interp.image;
            resources->interpOutputMemory = interp.memory;
            resources->interpOutputView = interp.view;
            auto lsfgOut = importOwnedRgbaAhb(g_vk->device, g_vk->memProps,
                                              resources->outputs[0].get(), ctxWidth, ctxHeight);
            resources->lsfgOutputImage = lsfgOut.image;
            resources->lsfgOutputMemory = lsfgOut.memory;
            resources->lsfgOutputView = lsfgOut.view;
        }
        const auto ownedImportEnd = std::chrono::steady_clock::now();
        LOGI("stage3.2 bootstrap timing: importOwnedRgbaAhb=%lldms total=%lldms",
             static_cast<long long>(elapsedMs(ownedImportStart, ownedImportEnd)),
             static_cast<long long>(elapsedMs(bootStart, ownedImportEnd)));

        // 编译内嵌的 YUV→RGBA compute shader（descriptor 套件需要 ycbcr conversion，
        // 留到 iii.b 接到首帧时再建）。
        const auto shaderModuleStart = std::chrono::steady_clock::now();
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
        {
            VkShaderModuleCreateInfo smCi{
                .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                .pNext = nullptr,
                .flags = 0,
                .codeSize = k_rgba_upscale_spv_size,
                .pCode = k_rgba_upscale_spv,
            };
            if (vkCreateShaderModule(g_vk->device, &smCi, nullptr, &resources->upscaleShaderModule) != VK_SUCCESS) {
                throw std::runtime_error("vkCreateShaderModule (rgba_upscale) failed");
            }
        }
        const auto shaderModuleEnd = std::chrono::steady_clock::now();
        LOGI("stage3.2 bootstrap timing: shaderModules=%lldms total=%lldms",
             static_cast<long long>(elapsedMs(shaderModuleStart, shaderModuleEnd)),
             static_cast<long long>(elapsedMs(bootStart, shaderModuleEnd)));
        LOGI("stage3.3a-iii.a: owned RGBA AHB imported ok fg=%ux%u present=%ux%u srcUvScale=%.4fx%.4f",
             resources->width, resources->height,
             resources->presentWidth, resources->presentHeight,
             resources->srcUvScaleX, resources->srcUvScaleY);

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

bool prewarmContext(int width, int height) {
    if (width <= 0 || height <= 0) {
        LOGW("stage3.2 prewarm skipped: invalid stream size %dx%d", width, height);
        return false;
    }

    const ContextBootState state = g_contextBootState.load(std::memory_order_acquire);
    if (state == ContextBootState::kReady) {
        LOGI("stage3.2 prewarm skipped: context already ready stream=%dx%d", width, height);
        return true;
    }
    if (state == ContextBootState::kFailed) {
        LOGW("stage3.2 prewarm skipped: context already failed stream=%dx%d", width, height);
        return false;
    }

    AHardwareBuffer_Desc desc{
        .width = static_cast<uint32_t>(width),
        .height = static_cast<uint32_t>(height),
        .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                 AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT,
        .stride = 0,
        .rfu0 = 0,
        .rfu1 = 0,
    };

    AHardwareBuffer* raw = nullptr;
    if (AHardwareBuffer_allocate(&desc, &raw) != 0 || raw == nullptr) {
        LOGW("stage3.2 prewarm failed: probe AHB allocate %dx%d", width, height);
        return false;
    }

    AhbPtr probeAhb(raw);
    LOGI("stage3.2 prewarm start: stream=%dx%d target=%d internalWidth=%d",
         width, height,
         g_outputFrameRate.load(std::memory_order_acquire),
         g_internalFramegenWidth.load(std::memory_order_acquire));
    return ensureContextBootstrapped(
        probeAhb.get(),
        width,
        height,
        AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
}

void reset() {
    std::lock_guard<std::mutex> lock(g_contextMutex);
    stopPresenterThread();
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
    g_adaptive = AdaptiveFramegenState{};
    if (g_outputWindow != nullptr) {
        ANativeWindow_release(g_outputWindow);
        g_outputWindow = nullptr;
        g_outputWindowConfiguredW = 0;
        g_outputWindowConfiguredH = 0;
        g_outputWindowHintedFps = 0;
        g_outputWindowBuffersRequested = false;
        g_blitCount.store(0, std::memory_order_relaxed);
    }
}

void setHdrEnabled(bool enabled) {
    const bool prev = g_hdrEnabled.exchange(enabled, std::memory_order_acq_rel);
    if (prev != enabled) {
        LOGI("setHdrEnabled: %d -> %d (effective on next bootstrap)",
             static_cast<int>(prev), static_cast<int>(enabled));
    }
}

void setOutputFrameRate(int32_t fps) {
    const int32_t clampedFps = std::max(0, fps);
    std::lock_guard<std::mutex> lock(g_contextMutex);
    const int32_t prev = g_outputFrameRate.exchange(clampedFps, std::memory_order_acq_rel);
    g_adaptive = AdaptiveFramegenState{};
    if (prev != clampedFps) {
        LOGI("stage3.3d: output target fps %d -> %d; adaptive state reset",
             prev, clampedFps);
        g_outputWindowHintedFps = 0;
        applyOutputWindowHintsLocked(false);
    }
}

void setTuningConfig(int32_t internalWidth,
                     int32_t presentMode,
                     int32_t slowLsfgThresholdMs,
                     int32_t presentQueueMax) {
    const int32_t clampedWidth = std::clamp(internalWidth, 0, 1920);
    const int32_t clampedPresentMode = presentMode == 1 ? 1 : 0;
    const int32_t clampedSlowThreshold = std::clamp(slowLsfgThresholdMs, 0, 100);
    const int32_t clampedQueueMax = std::clamp(presentQueueMax, 1, 12);

    std::lock_guard<std::mutex> lock(g_contextMutex);
    const int32_t prevWidth = g_internalFramegenWidth.exchange(clampedWidth, std::memory_order_acq_rel);
    const int32_t prevMode = g_presentMode.exchange(clampedPresentMode, std::memory_order_acq_rel);
    const int32_t prevSlow = g_slowLsfgThresholdMs.exchange(clampedSlowThreshold, std::memory_order_acq_rel);
    const int32_t prevQueue = g_presentQueueMax.exchange(clampedQueueMax, std::memory_order_acq_rel);
    if (prevWidth != clampedWidth ||
        prevMode != clampedPresentMode ||
        prevSlow != clampedSlowThreshold ||
        prevQueue != clampedQueueMax) {
        LOGI("stage3.3d: tuning width=%d mode=%d slowMs=%d queueMax=%d "
             "(prev width=%d mode=%d slowMs=%d queueMax=%d)",
             clampedWidth, clampedPresentMode, clampedSlowThreshold, clampedQueueMax,
             prevWidth, prevMode, prevSlow, prevQueue);
    }
}

void setOutputWindow(ANativeWindow* nativeWindow) {
    std::lock_guard<std::mutex> lock(g_contextMutex);
    if (g_outputWindow == nativeWindow) return;
    stopPresenterThread();
    if (g_outputWindow != nullptr) {
        ANativeWindow_release(g_outputWindow);
    }
    g_outputWindow = nativeWindow;
    g_outputWindowConfiguredW = 0;
    g_outputWindowConfiguredH = 0;
    g_outputWindowHintedFps = 0;
    g_outputWindowBuffersRequested = false;
    g_blitCount.store(0, std::memory_order_relaxed);
    LOGI("stage3.3c: setOutputWindow window=%p", nativeWindow);
    applyOutputWindowHintsLocked(false);
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
    VkPushConstantRange pcRange{
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = sizeof(YuvToRgbaPushConstants),
    };

    VkPipelineLayoutCreateInfo plCi{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .setLayoutCount = 1,
        .pSetLayouts = &g_context->dsLayout,
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &pcRange,
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
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3 },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 3 },
    };
    VkDescriptorPoolCreateInfo dpCi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .maxSets = 3,
        .poolSizeCount = 2,
        .pPoolSizes = poolSizes,
    };
    if (vkCreateDescriptorPool(device, &dpCi, nullptr, &g_context->dsPool) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkCreateDescriptorPool failed");
        return false;
    }

    VkDescriptorSetLayout setLayouts[3] = {
        g_context->dsLayout,
        g_context->dsLayout,
        g_context->dsLayout,
    };
    VkDescriptorSetAllocateInfo dsAi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .pNext = nullptr,
        .descriptorPool = g_context->dsPool,
        .descriptorSetCount = 3,
        .pSetLayouts = setLayouts,
    };
    if (vkAllocateDescriptorSets(device, &dsAi, g_context->dsSets) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.1: vkAllocateDescriptorSets failed");
        return false;
    }

    VkDescriptorImageInfo dstInfos[3]{
        { VK_NULL_HANDLE, g_context->input0View, VK_IMAGE_LAYOUT_GENERAL },
        { VK_NULL_HANDLE, g_context->input1View, VK_IMAGE_LAYOUT_GENERAL },
        { VK_NULL_HANDLE, g_context->realOutputView, VK_IMAGE_LAYOUT_GENERAL },
    };
    VkWriteDescriptorSet preWrites[3]{};
    for (int i = 0; i < 3; ++i) {
        preWrites[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        preWrites[i].dstSet = g_context->dsSets[i];
        preWrites[i].dstBinding = 1;
        preWrites[i].descriptorCount = 1;
        preWrites[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        preWrites[i].pImageInfo = &dstInfos[i];
    }
    vkUpdateDescriptorSets(device, 3, preWrites, 0, nullptr);

    VkSamplerCreateInfo rgbaSamplerCi{
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .magFilter = VK_FILTER_LINEAR,
        .minFilter = VK_FILTER_LINEAR,
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
    if (vkCreateSampler(device, &rgbaSamplerCi, nullptr, &g_context->rgbaSampler) != VK_SUCCESS) {
        LOGE("stage3.3u: vkCreateSampler failed");
        return false;
    }

    VkDescriptorSetLayoutBinding upscaleBindings[2]{};
    upscaleBindings[0].binding = 0;
    upscaleBindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    upscaleBindings[0].descriptorCount = 1;
    upscaleBindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    upscaleBindings[1].binding = 1;
    upscaleBindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    upscaleBindings[1].descriptorCount = 1;
    upscaleBindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo upscaleDslCi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .bindingCount = 2,
        .pBindings = upscaleBindings,
    };
    if (vkCreateDescriptorSetLayout(device, &upscaleDslCi, nullptr, &g_context->upscaleDsLayout) != VK_SUCCESS) {
        LOGE("stage3.3u: vkCreateDescriptorSetLayout failed");
        return false;
    }

    VkPipelineLayoutCreateInfo upscalePlCi{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .setLayoutCount = 1,
        .pSetLayouts = &g_context->upscaleDsLayout,
        .pushConstantRangeCount = 0,
        .pPushConstantRanges = nullptr,
    };
    if (vkCreatePipelineLayout(device, &upscalePlCi, nullptr, &g_context->upscalePipelineLayout) != VK_SUCCESS) {
        LOGE("stage3.3u: vkCreatePipelineLayout failed");
        return false;
    }

    VkComputePipelineCreateInfo upscalePipeCi{
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .stage = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = g_context->upscaleShaderModule,
            .pName = "main",
            .pSpecializationInfo = nullptr,
        },
        .layout = g_context->upscalePipelineLayout,
        .basePipelineHandle = VK_NULL_HANDLE,
        .basePipelineIndex = -1,
    };
    if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &upscalePipeCi, nullptr, &g_context->upscalePipeline) != VK_SUCCESS) {
        LOGE("stage3.3u: vkCreateComputePipelines failed");
        return false;
    }

    VkDescriptorPoolSize upscalePoolSizes[2]{
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1 },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1 },
    };
    VkDescriptorPoolCreateInfo upscaleDpCi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .maxSets = 1,
        .poolSizeCount = 2,
        .pPoolSizes = upscalePoolSizes,
    };
    if (vkCreateDescriptorPool(device, &upscaleDpCi, nullptr, &g_context->upscaleDsPool) != VK_SUCCESS) {
        LOGE("stage3.3u: vkCreateDescriptorPool failed");
        return false;
    }
    VkDescriptorSetAllocateInfo upscaleDsAi{
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .pNext = nullptr,
        .descriptorPool = g_context->upscaleDsPool,
        .descriptorSetCount = 1,
        .pSetLayouts = &g_context->upscaleDsLayout,
    };
    if (vkAllocateDescriptorSets(device, &upscaleDsAi, &g_context->upscaleDsSet) != VK_SUCCESS) {
        LOGE("stage3.3u: vkAllocateDescriptorSets failed");
        return false;
    }

    VkDescriptorImageInfo upscaleSrcInfo{
        .sampler = g_context->rgbaSampler,
        .imageView = g_context->lsfgOutputView,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkDescriptorImageInfo upscaleDstInfo{
        .sampler = VK_NULL_HANDLE,
        .imageView = g_context->interpOutputView,
        .imageLayout = VK_IMAGE_LAYOUT_GENERAL,
    };
    VkWriteDescriptorSet upscaleWrites[2]{};
    upscaleWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    upscaleWrites[0].dstSet = g_context->upscaleDsSet;
    upscaleWrites[0].dstBinding = 0;
    upscaleWrites[0].descriptorCount = 1;
    upscaleWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    upscaleWrites[0].pImageInfo = &upscaleSrcInfo;
    upscaleWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    upscaleWrites[1].dstSet = g_context->upscaleDsSet;
    upscaleWrites[1].dstBinding = 1;
    upscaleWrites[1].descriptorCount = 1;
    upscaleWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    upscaleWrites[1].pImageInfo = &upscaleDstInfo;
    vkUpdateDescriptorSets(device, 2, upscaleWrites, 0, nullptr);

    g_context->boundExternalFormat = fp.externalFormat;
    LOGI("stage3.3a-iii.b.1: ycbcr conversion + sampler + pipeline ready "
         "externalFormat=0x%llx model=%u range=%u chromaFilter=%d",
         static_cast<unsigned long long>(fp.externalFormat),
         static_cast<unsigned>(fp.suggestedYcbcrModel),
         static_cast<unsigned>(fp.suggestedYcbcrRange),
         static_cast<int>(cvtCi.chromaFilter));
    LOGI("stage3.3u: rgba upscale pipeline ready src=%ux%u dst=%ux%u",
         g_context->width, g_context->height,
         g_context->presentWidth, g_context->presentHeight);
    return true;
}

// 阶段 3.3c：把指定 RGBA8888 AHB 通过 CPU 拷贝回贴到 SurfaceView 的 ANativeWindow。
// 调用方必须持 g_contextMutex 且 AHB 内容已 GPU 完成（dispatch 后 vkWaitForFences /
// LSFG waitIdle）。失败只打日志，不影响后续帧。tag 仅用于日志区分。
void blitAhbToWindowLocked(AHardwareBuffer* srcAhb, const char* tag) {
    if (g_outputWindow == nullptr || srcAhb == nullptr) {
        return;
    }
    const auto totalStart = std::chrono::steady_clock::now();

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
        g_outputWindowBuffersRequested = false;
        applyOutputWindowHintsLocked(true);
        LOGI("stage3.3c: configured output window %dx%d (src stride=%d px)",
             srcW, srcH, srcStridePx);
    }

    void* srcPtr = nullptr;
    const auto ahbLockStart = std::chrono::steady_clock::now();
    const int lockResult = AHardwareBuffer_lock(
        srcAhb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &srcPtr);
    const auto ahbLockEnd = std::chrono::steady_clock::now();
    if (lockResult != 0 || srcPtr == nullptr) {
        LOGE("stage3.3c: AHardwareBuffer_lock(%s) rc=%d ptr=%p", tag, lockResult, srcPtr);
        return;
    }

    ANativeWindow_Buffer dst{};
    const auto windowLockStart = std::chrono::steady_clock::now();
    const int32_t rc = ANativeWindow_lock(g_outputWindow, &dst, nullptr);
    const auto windowLockEnd = std::chrono::steady_clock::now();
    if (rc != 0) {
        LOGE("stage3.3c: ANativeWindow_lock(%s) rc=%d", tag, rc);
        AHardwareBuffer_unlock(srcAhb, nullptr);
        return;
    }

    const auto copyStart = std::chrono::steady_clock::now();
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
    const auto copyEnd = std::chrono::steady_clock::now();

    const auto postStart = std::chrono::steady_clock::now();
    const int32_t postRc = ANativeWindow_unlockAndPost(g_outputWindow);
    const auto postEnd = std::chrono::steady_clock::now();
    if (postRc != 0) {
        LOGE("stage3.3c: ANativeWindow_unlockAndPost(%s) rc=%d", tag, postRc);
    }

    const auto ahbUnlockStart = std::chrono::steady_clock::now();
    AHardwareBuffer_unlock(srcAhb, nullptr);
    const auto totalEnd = std::chrono::steady_clock::now();

    const uint64_t blitCount = g_blitCount.fetch_add(1, std::memory_order_relaxed) + 1;
    const int64_t totalMs = elapsedMs(totalStart, totalEnd);
    const bool logSample = (blitCount == 1 || (blitCount % 120) == 0);
    if (logSample || totalMs >= kSlowBlitMs) {
        LOGI("stage3.3c: blit timing tag=%s count=%llu total=%lldms "
             "ahbLock=%lldms winLock=%lldms copy=%lldms post=%lldms ahbUnlock=%lldms "
             "src=%dx%d/stride=%d dst=%dx%d/stride=%d",
             tag,
             static_cast<unsigned long long>(blitCount),
             static_cast<long long>(totalMs),
             static_cast<long long>(elapsedMs(ahbLockStart, ahbLockEnd)),
             static_cast<long long>(elapsedMs(windowLockStart, windowLockEnd)),
             static_cast<long long>(elapsedMs(copyStart, copyEnd)),
             static_cast<long long>(elapsedMs(postStart, postEnd)),
             static_cast<long long>(elapsedMs(ahbUnlockStart, totalEnd)),
             srcW, srcH, srcStridePx,
             dst.width, dst.height, dst.stride);
    }
}

bool enqueueAhbToPresenterLocked(AHardwareBuffer* srcAhb,
                                 const char* tag,
                                 uint64_t sourceFrame,
                                 uint32_t sourceOrder) {
    if (g_outputWindow == nullptr || srcAhb == nullptr) {
        return false;
    }

    const auto totalStart = std::chrono::steady_clock::now();
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(srcAhb, &desc);
    const int32_t srcW = static_cast<int32_t>(desc.width);
    const int32_t srcH = static_cast<int32_t>(desc.height);
    const int32_t srcStridePx = static_cast<int32_t>(desc.stride);
    if (srcW <= 0 || srcH <= 0) {
        return false;
    }

    const int32_t displayW = (g_context != nullptr && g_context->presentWidth > 0)
        ? static_cast<int32_t>(g_context->presentWidth)
        : srcW;
    const int32_t displayH = (g_context != nullptr && g_context->presentHeight > 0)
        ? static_cast<int32_t>(g_context->presentHeight)
        : srcH;

    if (g_outputWindowConfiguredW != displayW || g_outputWindowConfiguredH != displayH) {
        const int32_t rc = ANativeWindow_setBuffersGeometry(
            g_outputWindow, displayW, displayH, WINDOW_FORMAT_RGBA_8888);
        if (rc != 0) {
            LOGE("stage3.3c: ANativeWindow_setBuffersGeometry rc=%d w=%d h=%d", rc, displayW, displayH);
            return false;
        }
        constexpr int32_t kAdataspaceSrgb = 142671872;
        ANativeWindow_setBuffersDataSpace(g_outputWindow, kAdataspaceSrgb);
        g_outputWindowConfiguredW = displayW;
        g_outputWindowConfiguredH = displayH;
        g_outputWindowBuffersRequested = false;
        applyOutputWindowHintsLocked(true);
        LOGI("stage3.3c: configured async output window %dx%d (src=%dx%d stride=%d px)",
             displayW, displayH, srcW, srcH, srcStridePx);
    }

    void* srcPtr = nullptr;
    const auto ahbLockStart = std::chrono::steady_clock::now();
    const int lockResult = AHardwareBuffer_lock(
        srcAhb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &srcPtr);
    const auto ahbLockEnd = std::chrono::steady_clock::now();
    if (lockResult != 0 || srcPtr == nullptr) {
        LOGE("stage3.3c: AHardwareBuffer_lock enqueue(%s) rc=%d ptr=%p", tag, lockResult, srcPtr);
        return false;
    }

    PresentFrame frame{};
    frame.width = srcW;
    frame.height = srcH;
    frame.displayWidth = displayW;
    frame.displayHeight = displayH;
    frame.tag = tag != nullptr ? tag : "";
    try {
        frame.rgba.resize(static_cast<size_t>(srcW) * static_cast<size_t>(srcH) * 4u);
    } catch (const std::exception& e) {
        AHardwareBuffer_unlock(srcAhb, nullptr);
        LOGE("stage3.3c: staging allocation failed tag=%s err=%s", tag, e.what());
        return false;
    }

    const auto copyStart = std::chrono::steady_clock::now();
    const uint8_t* srcRow = static_cast<const uint8_t*>(srcPtr);
    uint8_t* dstRow = frame.rgba.data();
    const size_t srcRowBytes = static_cast<size_t>(srcStridePx) * 4u;
    const size_t dstRowBytes = static_cast<size_t>(srcW) * 4u;
    for (int32_t y = 0; y < srcH; ++y) {
        std::memcpy(dstRow, srcRow, dstRowBytes);
        srcRow += srcRowBytes;
        dstRow += dstRowBytes;
    }
    const auto copyEnd = std::chrono::steady_clock::now();
    AHardwareBuffer_unlock(srcAhb, nullptr);

    ANativeWindow_acquire(g_outputWindow);
    frame.window = g_outputWindow;
    frame.sourceFrame = sourceFrame;
    frame.sourceOrder = sourceOrder;
    ensurePresenterThreadLocked();

    uint64_t enqueued = 0;
    size_t queueDepth = 0;
    {
        std::lock_guard<std::mutex> lock(g_presentMutex);
        const size_t maxPresentQueue = static_cast<size_t>(
            std::clamp(g_presentQueueMax.load(std::memory_order_acquire), 1, 12));
        if (g_presentQueue.size() >= maxPresentQueue) {
            PresentFrame dropped = std::move(g_presentQueue.front());
            g_presentQueue.pop_front();
            releasePresentFrame(dropped);
            g_presentDropped += 1;
            if (g_presentDropped == 1 || (g_presentDropped % 120) == 0) {
                LOGW("stage3.3c: presenter queue drop count=%llu depth=%zu",
                     static_cast<unsigned long long>(g_presentDropped),
                     g_presentQueue.size());
            }
        }
        g_presentQueue.emplace_back(std::move(frame));
        g_presentEnqueued += 1;
        enqueued = g_presentEnqueued;
        queueDepth = g_presentQueue.size();
    }
    g_presentCv.notify_one();

    const int64_t totalMs = elapsedMs(totalStart, std::chrono::steady_clock::now());
    const bool logSample = (enqueued == 1 || (enqueued % 120) == 0);
    if (logSample || totalMs >= 4) {
        LOGI("stage3.3c: enqueue timing tag=%s count=%llu src=%llu/%u total=%lldms "
             "ahbLock=%lldms copy=%lldms depth=%zu src=%dx%d/stride=%d display=%dx%d",
             tag,
             static_cast<unsigned long long>(enqueued),
             static_cast<unsigned long long>(sourceFrame),
             sourceOrder,
             static_cast<long long>(totalMs),
             static_cast<long long>(elapsedMs(ahbLockStart, ahbLockEnd)),
             static_cast<long long>(elapsedMs(copyStart, copyEnd)),
             queueDepth,
             srcW, srcH, srcStridePx,
             displayW, displayH);
    }
    return true;
}

bool ensureDispatchObjectsLocked() {
    if (g_context == nullptr || g_vk == nullptr || g_vk->device == VK_NULL_HANDLE) {
        return false;
    }
    VkDevice device = g_vk->device;
    if (g_context->ownerCmdPool == VK_NULL_HANDLE) {
        g_context->ownerCmdPool = g_vk->cmdPool;
    }

    if (g_context->dispatchCmd == VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo cmdAi{
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
            .pNext = nullptr,
            .commandPool = g_vk->cmdPool,
            .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
            .commandBufferCount = 1,
        };
        if (vkAllocateCommandBuffers(device, &cmdAi, &g_context->dispatchCmd) != VK_SUCCESS) {
            LOGE("stage3.3a-iii.b.2: vkAllocateCommandBuffers failed");
            return false;
        }
    }

    if (g_context->dispatchFence == VK_NULL_HANDLE) {
        VkFenceCreateInfo fci{
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
        };
        if (vkCreateFence(device, &fci, nullptr, &g_context->dispatchFence) != VK_SUCCESS) {
            LOGE("stage3.3a-iii.b.2: vkCreateFence failed");
            return false;
        }
    }
    return true;
}

// 阶段 3.3a-iii.b.2：对当前 decoder VkImage + view 提交一次 YUV→RGBA compute dispatch，
// 写到 ping-pong input{0,1}Image。调用方必须持 g_contextMutex，且 g_vk / g_context / pipeline 必备。
// decoder image 假设 AHB queue family 为 FOREIGN_EXT；本函数做 acquire barrier 接管。
// 返回 false 表示本帧 dispatch 失败（不影响后续）。
bool ensureUpscaleObjectsLocked() {
    if (g_context == nullptr || g_vk == nullptr || g_vk->device == VK_NULL_HANDLE) {
        return false;
    }
    VkDevice device = g_vk->device;
    if (g_context->ownerCmdPool == VK_NULL_HANDLE) {
        g_context->ownerCmdPool = g_vk->cmdPool;
    }

    if (g_context->upscaleCmd == VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo cmdAi{
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
            .pNext = nullptr,
            .commandPool = g_vk->cmdPool,
            .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
            .commandBufferCount = 1,
        };
        if (vkAllocateCommandBuffers(device, &cmdAi, &g_context->upscaleCmd) != VK_SUCCESS) {
            LOGE("stage3.3u: vkAllocateCommandBuffers failed");
            return false;
        }
    }

    if (g_context->upscaleFence == VK_NULL_HANDLE) {
        VkFenceCreateInfo fci{
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            .pNext = nullptr,
            .flags = 0,
        };
        if (vkCreateFence(device, &fci, nullptr, &g_context->upscaleFence) != VK_SUCCESS) {
            LOGE("stage3.3u: vkCreateFence failed");
            return false;
        }
    }
    return true;
}

bool upscaleInterpLocked(bool logThisFrame, int64_t* outWaitMs) {
    if (outWaitMs != nullptr) {
        *outWaitMs = -1;
    }
    if (g_context == nullptr || g_vk == nullptr ||
        g_context->upscalePipeline == VK_NULL_HANDLE ||
        g_context->upscaleDsSet == VK_NULL_HANDLE) {
        return false;
    }
    if (!ensureUpscaleObjectsLocked()) {
        return false;
    }

    VkDevice device = g_vk->device;
    VkCommandBuffer cmd = g_context->upscaleCmd;
    VkFence fence = g_context->upscaleFence;
    vkResetCommandBuffer(cmd, 0);
    vkResetFences(device, 1, &fence);

    VkCommandBufferBeginInfo cmdBi{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
        .pInheritanceInfo = nullptr,
    };
    vkBeginCommandBuffer(cmd, &cmdBi);

    VkImageMemoryBarrier barriers[2]{};
    barriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[0].srcAccessMask = 0;
    barriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    barriers[0].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    barriers[0].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    barriers[0].dstQueueFamilyIndex = g_vk->queueFamilyIndex;
    barriers[0].image = g_context->lsfgOutputImage;
    barriers[0].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    barriers[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[1].srcAccessMask = 0;
    barriers[1].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barriers[1].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barriers[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[1].image = g_context->interpOutputImage;
    barriers[1].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0,
                         0, nullptr,
                         0, nullptr,
                         2, barriers);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_context->upscalePipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_context->upscalePipelineLayout, 0, 1, &g_context->upscaleDsSet,
                            0, nullptr);
    const uint32_t gx = (g_context->presentWidth + 7U) / 8U;
    const uint32_t gy = (g_context->presentHeight + 7U) / 8U;
    vkCmdDispatch(cmd, gx, gy, 1);

    VkImageMemoryBarrier releaseBarrier{};
    releaseBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    releaseBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    releaseBarrier.dstAccessMask = 0;
    releaseBarrier.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    releaseBarrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    releaseBarrier.srcQueueFamilyIndex = g_vk->queueFamilyIndex;
    releaseBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    releaseBarrier.image = g_context->lsfgOutputImage;
    releaseBarrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0,
                         0, nullptr,
                         0, nullptr,
                         1, &releaseBarrier);

    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) {
        LOGE("stage3.3u: vkEndCommandBuffer failed");
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
        LOGE("stage3.3u: vkQueueSubmit failed res=%d", sres);
        return false;
    }

    const auto t0 = std::chrono::steady_clock::now();
    VkResult wres = vkWaitForFences(device, 1, &fence, VK_TRUE, 1'000'000'000ULL);
    const auto t1 = std::chrono::steady_clock::now();
    const int64_t waitMs = elapsedMs(t0, t1);
    if (outWaitMs != nullptr) {
        *outWaitMs = waitMs;
    }
    if (wres != VK_SUCCESS) {
        LOGE("stage3.3u: vkWaitForFences res=%d", wres);
        vkQueueWaitIdle(g_vk->queue);
        return false;
    }
    if (logThisFrame || waitMs >= 4) {
        LOGI("stage3.3u: upscale ok wait=%lldms src=%ux%u dst=%ux%u",
             static_cast<long long>(waitMs),
             g_context->width, g_context->height,
             g_context->presentWidth, g_context->presentHeight);
    }
    return true;
}

// Adaptive pacing policy. Keep this native-side because decoder timestamps and LSFG cost
// are both observed here, not in the Java setup path.
bool shouldBypassFramegenLocked(int64_t timestampNs, float observedInputFps, bool logThisFrame) {
    const int32_t targetFps = g_outputFrameRate.load(std::memory_order_acquire);
    if (targetFps < 30) {
        if (timestampNs > 0) {
            g_adaptive.lastTimestampNs = timestampNs;
        }
        return false;
    }

    if (observedInputFps > 1.0F && observedInputFps < 500.0F) {
        if (g_adaptive.inputFpsEma <= 0.0) {
            g_adaptive.inputFpsEma = observedInputFps;
        } else {
            g_adaptive.inputFpsEma = (g_adaptive.inputFpsEma * 0.88) +
                                     (static_cast<double>(observedInputFps) * 0.12);
        }
    } else if (timestampNs > 0 && g_adaptive.lastTimestampNs > 0 && timestampNs > g_adaptive.lastTimestampNs) {
        const double deltaMs = static_cast<double>(timestampNs - g_adaptive.lastTimestampNs) / 1'000'000.0;
        if (deltaMs >= 1.0 && deltaMs <= 250.0) {
            const double instantFps = 1000.0 / deltaMs;
            if (g_adaptive.inputFpsEma <= 0.0) {
                g_adaptive.inputFpsEma = instantFps;
            } else {
                g_adaptive.inputFpsEma = (g_adaptive.inputFpsEma * 0.88) + (instantFps * 0.12);
            }

        }
    }
    if (g_adaptive.inputFpsEma > 0.0) {
        if (targetFps >= 100) {
            if (g_adaptive.highInputBypass) {
                LOGI("stage3.3d: adaptive exit high-input bypass for 2x target=%d inputEma=%.1f",
                     targetFps, g_adaptive.inputFpsEma);
            }
            g_adaptive.highInputBypass = false;
            g_adaptive.highInputFrames = 0;
            g_adaptive.lowInputFrames = 0;
        } else {
            const double enterRatio = 0.85;
            const double exitRatio = 0.70;
            const double enterFps = static_cast<double>(targetFps) * enterRatio;
            const double exitFps = static_cast<double>(targetFps) * exitRatio;
            if (!g_adaptive.highInputBypass) {
                if (g_adaptive.inputFpsEma >= enterFps) {
                    g_adaptive.highInputFrames += 1;
                    if (g_adaptive.highInputFrames >= 4) {
                        g_adaptive.highInputBypass = true;
                        g_adaptive.highInputFrames = 0;
                        g_adaptive.lowInputFrames = 0;
                        LOGI("stage3.3d: adaptive enter high-input bypass target=%d inputEma=%.1f",
                             targetFps, g_adaptive.inputFpsEma);
                    }
                } else {
                    g_adaptive.highInputFrames = 0;
                }
            } else {
                if (g_adaptive.inputFpsEma <= exitFps) {
                    g_adaptive.lowInputFrames += 1;
                    if (g_adaptive.lowInputFrames >= 24) {
                        g_adaptive.highInputBypass = false;
                        g_adaptive.highInputFrames = 0;
                        g_adaptive.lowInputFrames = 0;
                        LOGI("stage3.3d: adaptive exit high-input bypass target=%d inputEma=%.1f",
                             targetFps, g_adaptive.inputFpsEma);
                    }
                } else {
                    g_adaptive.lowInputFrames = 0;
                }
            }
        }
    }
    if (timestampNs > 0) {
        g_adaptive.lastTimestampNs = timestampNs;
    }

    if (g_adaptive.slowCooldownFrames > 0) {
        g_adaptive.slowCooldownFrames -= 1;
    }

    const bool bypass = g_adaptive.highInputBypass || g_adaptive.slowCooldownFrames > 0;
    if (logThisFrame) {
        LOGI("stage3.3d: adaptive target=%d inputEma=%.1f bypass=%d high=%d cooldown=%u",
             targetFps,
             g_adaptive.inputFpsEma,
             static_cast<int>(bypass),
             static_cast<int>(g_adaptive.highInputBypass),
             g_adaptive.slowCooldownFrames);
    }
    return bypass;
}

void recordLsfgCostLocked(int64_t waitIdleMs, bool logThisFrame) {
    const int32_t targetFps = g_outputFrameRate.load(std::memory_order_acquire);
    if (targetFps < 30 || waitIdleMs < 0) {
        return;
    }

    const double targetBudgetMs = 1000.0 / std::max(1.0, static_cast<double>(targetFps));
    const int32_t configuredSlowMs = g_slowLsfgThresholdMs.load(std::memory_order_acquire);
    const double slowThresholdMs = configuredSlowMs > 0
        ? static_cast<double>(configuredSlowMs)
        : std::max(18.0, targetBudgetMs * 1.25);

    if (static_cast<double>(waitIdleMs) > slowThresholdMs) {
        g_adaptive.slowLsfgFrames += 1;
        if (g_adaptive.slowLsfgFrames >= 2) {
            g_adaptive.slowCooldownFrames = static_cast<uint32_t>(std::max(60, targetFps * 2));
            g_adaptive.slowLsfgFrames = 0;
            LOGW("stage3.3d: adaptive enter slow-LSFG cooldown waitIdle=%lldms threshold=%.1f frames=%u",
                 static_cast<long long>(waitIdleMs),
                 slowThresholdMs,
                 g_adaptive.slowCooldownFrames);
        }
    } else {
        g_adaptive.slowLsfgFrames = 0;
    }

    if (logThisFrame) {
        LOGI("stage3.3d: lsfg cost waitIdle=%lldms threshold=%.1f slowFrames=%u cooldown=%u",
             static_cast<long long>(waitIdleMs),
             slowThresholdMs,
             g_adaptive.slowLsfgFrames,
             g_adaptive.slowCooldownFrames);
    }
}

void destroyDecoderImportLocked(DecoderAhbImport& import) {
    if (g_vk == nullptr || g_vk->device == VK_NULL_HANDLE) {
        return;
    }
    if (import.view != VK_NULL_HANDLE) {
        vkDestroyImageView(g_vk->device, import.view, nullptr);
        import.view = VK_NULL_HANDLE;
    }
    if (import.image != VK_NULL_HANDLE) {
        vkDestroyImage(g_vk->device, import.image, nullptr);
        import.image = VK_NULL_HANDLE;
    }
    if (import.memory != VK_NULL_HANDLE) {
        vkFreeMemory(g_vk->device, import.memory, nullptr);
        import.memory = VK_NULL_HANDLE;
    }
    import.ahb.reset();
    import.key = nullptr;
}

DecoderAhbImport* findDecoderImportLocked(AHardwareBuffer* decoderAhb, uint64_t useCount) {
    if (g_context == nullptr || decoderAhb == nullptr) {
        return nullptr;
    }
    for (auto& import : g_context->decoderImports) {
        if (import.key == decoderAhb && import.image != VK_NULL_HANDLE && import.view != VK_NULL_HANDLE) {
            import.lastUse = useCount;
            return &import;
        }
    }
    return nullptr;
}

DecoderAhbImport* createDecoderImportLocked(
        AHardwareBuffer* decoderAhb,
        const VkAndroidHardwareBufferFormatPropertiesANDROID& formatProps,
        const VkAndroidHardwareBufferPropertiesANDROID& ahbProps,
        const AHardwareBuffer_Desc& desc,
        uint64_t useCount,
        bool logImport) {
    if (g_context == nullptr || g_vk == nullptr || g_vk->device == VK_NULL_HANDLE) {
        return nullptr;
    }

    constexpr size_t kMaxCachedDecoderImports = 24;
    if (g_context->decoderImports.size() >= kMaxCachedDecoderImports) {
        auto oldest = std::min_element(
            g_context->decoderImports.begin(),
            g_context->decoderImports.end(),
            [](const DecoderAhbImport& a, const DecoderAhbImport& b) {
                return a.lastUse < b.lastUse;
            });
        if (oldest != g_context->decoderImports.end()) {
            LOGI("stage3.3a-ii: evict decoder AHB cache key=%p size=%zu",
                 oldest->key, g_context->decoderImports.size());
            destroyDecoderImportLocked(*oldest);
            g_context->decoderImports.erase(oldest);
        }
    }

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

    DecoderAhbImport import{};
    import.key = decoderAhb;
    import.width = desc.width;
    import.height = desc.height;
    import.stride = desc.stride;
    import.externalFormat = formatProps.externalFormat;
    import.allocationSize = ahbProps.allocationSize;
    import.memoryTypeBits = ahbProps.memoryTypeBits;
    import.lastUse = useCount;

    AHardwareBuffer_acquire(decoderAhb);
    import.ahb = AhbPtr(decoderAhb);

    VkResult res = vkCreateImage(g_vk->device, &imgCi, nullptr, &import.image);
    if (res != VK_SUCCESS) {
        LOGE("stage3.3a-ii: vkCreateImage (external) failed res=%d externalFormat=0x%llx",
             res, static_cast<unsigned long long>(formatProps.externalFormat));
        return nullptr;
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
        destroyDecoderImportLocked(import);
        return nullptr;
    }

    VkMemoryDedicatedAllocateInfo dedicated{
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = nullptr,
        .image = import.image,
        .buffer = VK_NULL_HANDLE,
    };
    VkImportAndroidHardwareBufferInfoANDROID importInfo{
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .pNext = &dedicated,
        .buffer = import.ahb.get(),
    };
    VkMemoryAllocateInfo alloc{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &importInfo,
        .allocationSize = ahbProps.allocationSize,
        .memoryTypeIndex = typeIndex,
    };
    res = vkAllocateMemory(g_vk->device, &alloc, nullptr, &import.memory);
    if (res != VK_SUCCESS) {
        LOGE("stage3.3a-ii: vkAllocateMemory (AHB import) failed res=%d size=%llu",
             res, static_cast<unsigned long long>(ahbProps.allocationSize));
        destroyDecoderImportLocked(import);
        return nullptr;
    }

    res = vkBindImageMemory(g_vk->device, import.image, import.memory, 0);
    if (res != VK_SUCCESS) {
        LOGE("stage3.3a-ii: vkBindImageMemory failed res=%d", res);
        destroyDecoderImportLocked(import);
        return nullptr;
    }

    VkSamplerYcbcrConversionInfo cvtInfoView{
        .sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO,
        .pNext = nullptr,
        .conversion = g_context->ycbcrConversion,
    };
    VkImageViewCreateInfo viewCi{
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = &cvtInfoView,
        .flags = 0,
        .image = import.image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = VK_FORMAT_UNDEFINED,
        .components = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                       VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY},
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
    res = vkCreateImageView(g_vk->device, &viewCi, nullptr, &import.view);
    if (res != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.2: vkCreateImageView (decoder ycbcr) failed res=%d", res);
        destroyDecoderImportLocked(import);
        return nullptr;
    }

    g_context->decoderImports.emplace_back(std::move(import));
    DecoderAhbImport& cached = g_context->decoderImports.back();
    if (logImport) {
        LOGI("stage3.3a-ii: decoder AHB import cached key=%p externalFormat=0x%llx "
             "size=%llu memBits=0x%x samplerYcbcrFeatures=0x%x cache=%zu",
             cached.key,
             static_cast<unsigned long long>(cached.externalFormat),
             static_cast<unsigned long long>(cached.allocationSize),
             cached.memoryTypeBits,
             formatProps.formatFeatures,
             g_context->decoderImports.size());
    }
    return &cached;
}

// Per-frame decoder AHB import target: convert YUV to the LSFG shared RGBA input,
// then either bypass to the real frame or invoke LSFG for one interpolated output.
bool dispatchYuvToRgbaLocked(DecoderAhbImport& decoderImport,
                             int64_t timestampNs, float observedInputFps) {
    if (g_context == nullptr || g_vk == nullptr) {
        return false;
    }
    if (g_context->pipeline == VK_NULL_HANDLE ||
        g_context->dsSets[0] == VK_NULL_HANDLE ||
        g_context->dsSets[2] == VK_NULL_HANDLE) {
        return false;
    }
    VkDevice device = g_vk->device;
    const auto frameStart = std::chrono::steady_clock::now();
    int64_t waitFenceMs = 0;
    int64_t lsfgPresentMs = 0;
    int64_t lsfgWaitIdleMs = -1;

    const uint64_t nextDispatchCount = g_context->dispatchCount + 1;
    const bool logThisFrame = (nextDispatchCount == 1 || (nextDispatchCount % 60) == 0);
    const int64_t prevTimestampNs = g_adaptive.lastTimestampNs;
    const bool bypassFramegen = shouldBypassFramegenLocked(timestampNs, observedInputFps, logThisFrame);
    double inputGapMs = 0.0;
    bool cadenceBreak = false;
    if (timestampNs > 0 && prevTimestampNs > 0 && timestampNs > prevTimestampNs) {
        inputGapMs = static_cast<double>(timestampNs - prevTimestampNs) / 1'000'000.0;
        const int32_t targetFps = g_outputFrameRate.load(std::memory_order_acquire);
        if (targetFps >= 100) {
            const double expectedSourceFps = std::max(30.0, static_cast<double>(targetFps) * 0.5);
            const double expectedGapMs = 1000.0 / expectedSourceFps;
            const double breakGapMs = std::max(28.0, expectedGapMs * 1.65);
            cadenceBreak = inputGapMs > breakGapMs;
        }
    }
    if (cadenceBreak) {
        g_adaptive.cadenceBreakFrames += 1;
        g_adaptive.cadenceSuppressFrames = std::max(
            g_adaptive.cadenceSuppressFrames,
            kCadenceRecoverySuppressFrames);
        clearPresenterQueue("cadence");
        if (g_adaptive.cadenceBreakFrames == 1 ||
            (g_adaptive.cadenceBreakFrames % 60) == 0 ||
            logThisFrame) {
            LOGW("stage3.3d: cadence break suppress interp gap=%.2fms count=%u recover=%u inputEma=%.1f",
                 inputGapMs,
                 g_adaptive.cadenceBreakFrames,
                 g_adaptive.cadenceSuppressFrames,
                 g_adaptive.inputFpsEma);
        }
    } else {
        g_adaptive.cadenceBreakFrames = 0;
    }
    const bool cadenceSuppressInterp = g_adaptive.cadenceSuppressFrames > 0;
    const uint32_t slot = g_context->pingPongIndex & 1U;
    const VkImage decoderImage = decoderImport.image;
    const VkImageView decoderView = decoderImport.view;
    const VkImage inputImage = (slot == 0) ? g_context->input0Image : g_context->input1Image;
    const VkImage realOutputImage = g_context->realOutputImage;

    // 1. 用本帧 decoder view 覆写 binding 0（immutable sampler 由 layout 提供，sampler 字段忽略）。
    VkDescriptorImageInfo srcInfo{
        .sampler = VK_NULL_HANDLE,
        .imageView = decoderView,
        .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
    };
    VkWriteDescriptorSet writeSrc[2]{};
    writeSrc[0] = {
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
    writeSrc[1] = writeSrc[0];
    writeSrc[1].dstSet = g_context->dsSets[2];
    vkUpdateDescriptorSets(device, 2, writeSrc, 0, nullptr);

    if (!ensureDispatchObjectsLocked()) {
        return false;
    }
    VkCommandBuffer cmd = g_context->dispatchCmd;
    VkFence fence = g_context->dispatchFence;
    vkResetCommandBuffer(cmd, 0);
    vkResetFences(device, 1, &fence);

    VkCommandBufferBeginInfo cmdBi{
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
        .pInheritanceInfo = nullptr,
    };
    vkBeginCommandBuffer(cmd, &cmdBi);

    // 3. acquire barriers:
    //  - decoder image: MediaCodec/ImageReader 外部队列 → 我方 queue family；
    //  - current LSFG input image: LSFG 外部队列 → 我方 queue family。
    // input 首次写入时旧内容不保留；之后它会由 LSFG release 回 GENERAL/EXTERNAL。
    VkImageMemoryBarrier barriers[3]{};
    barriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[0].srcAccessMask = 0;
    barriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    barriers[0].oldLayout = decoderImport.layoutInitialized
        ? VK_IMAGE_LAYOUT_GENERAL
        : VK_IMAGE_LAYOUT_UNDEFINED;
    barriers[0].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    barriers[0].dstQueueFamilyIndex = g_vk->queueFamilyIndex;
    barriers[0].image = decoderImage;
    barriers[0].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    barriers[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[1].srcAccessMask = 0;
    barriers[1].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barriers[1].oldLayout = g_context->inputInitialized[slot]
        ? VK_IMAGE_LAYOUT_GENERAL
        : VK_IMAGE_LAYOUT_UNDEFINED;
    barriers[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    barriers[1].dstQueueFamilyIndex = g_vk->queueFamilyIndex;
    barriers[1].image = inputImage;
    barriers[1].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    barriers[2].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barriers[2].srcAccessMask = 0;
    barriers[2].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barriers[2].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barriers[2].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barriers[2].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[2].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[2].image = realOutputImage;
    barriers[2].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0,
                         0, nullptr,
                         0, nullptr,
                         3, barriers);

    // 4. dispatch。
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g_context->pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_context->pipelineLayout, 0, 1, &g_context->dsSets[slot],
                            0, nullptr);
    const YuvToRgbaPushConstants pc{
        .srcUvScaleX = g_context->srcUvScaleX,
        .srcUvScaleY = g_context->srcUvScaleY,
    };
    vkCmdPushConstants(cmd,
                       g_context->pipelineLayout,
                       VK_SHADER_STAGE_COMPUTE_BIT,
                       0,
                       sizeof(pc),
                       &pc);
    const uint32_t gx = (g_context->width + 7U) / 8U;
    const uint32_t gy = (g_context->height + 7U) / 8U;
    vkCmdDispatch(cmd, gx, gy, 1);

    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g_context->pipelineLayout, 0, 1, &g_context->dsSets[2],
                            0, nullptr);
    const uint32_t presentGx = (g_context->presentWidth + 7U) / 8U;
    const uint32_t presentGy = (g_context->presentHeight + 7U) / 8U;
    vkCmdDispatch(cmd, presentGx, presentGy, 1);

    VkImageMemoryBarrier releaseBarriers[2]{};
    releaseBarriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    releaseBarriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    releaseBarriers[0].dstAccessMask = 0;
    releaseBarriers[0].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    releaseBarriers[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    releaseBarriers[0].srcQueueFamilyIndex = g_vk->queueFamilyIndex;
    releaseBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    releaseBarriers[0].image = inputImage;
    releaseBarriers[0].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    releaseBarriers[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    releaseBarriers[1].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    releaseBarriers[1].dstAccessMask = 0;
    releaseBarriers[1].oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    releaseBarriers[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    releaseBarriers[1].srcQueueFamilyIndex = g_vk->queueFamilyIndex;
    releaseBarriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    releaseBarriers[1].image = decoderImage;
    releaseBarriers[1].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};

    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0,
                         0, nullptr,
                         0, nullptr,
                         2, releaseBarriers);

    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.2: vkEndCommandBuffer failed");
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
        return false;
    }

    // 1s 上限，足够覆盖 4K dispatch；超时算失败。
    const auto t0 = std::chrono::steady_clock::now();
    VkResult wres = vkWaitForFences(device, 1, &fence, VK_TRUE, 1'000'000'000ULL);
    const auto t1 = std::chrono::steady_clock::now();
    waitFenceMs = elapsedMs(t0, t1);
    if (wres != VK_SUCCESS) {
        LOGE("stage3.3a-iii.b.2: vkWaitForFences res=%d", wres);
        vkQueueWaitIdle(g_vk->queue);
        return false;
    }

    g_context->dispatchCount += 1;
    g_context->inputInitialized[slot] = true;
    decoderImport.layoutInitialized = true;
    if (logThisFrame) {
        LOGI("stage3.3a-iii.b.2: dispatch ok slot=%u count=%llu fgGroups=%ux%u fg=%ux%u "
             "realGroups=%ux%u present=%ux%u waitFence=%lldms",
             slot,
             static_cast<unsigned long long>(g_context->dispatchCount),
             gx, gy, g_context->width, g_context->height,
             presentGx, presentGy, g_context->presentWidth, g_context->presentHeight,
             static_cast<long long>(waitFenceMs));
    }

    // 阶段 3.3b：input AHB 内容已就绪，请求 LSFG 用 input0/input1 生成中间帧到 output AHB。
    // LSFG 内部按调用次数 frameIdx % 2 选 input slot，与本侧 pingPongIndex 对齐。
    // 第一次调用时另一 slot 还没有数据，生成结果是 garbage —— output 还没回贴 SurfaceView，
    // 视觉无副作用；只要不报 vulkan error / 不 crash 就算通过。
    AHardwareBuffer* realAhb = g_context->realOutput.get();
    if (bypassFramegen) {
        const auto b0 = std::chrono::steady_clock::now();
        enqueueAhbToPresenterLocked(realAhb, "real-bypass", g_context->dispatchCount, 0);
        const auto b1 = std::chrono::steady_clock::now();
        const int64_t enqueueMs = elapsedMs(b0, b1);
        const int64_t totalMs = elapsedMs(frameStart, b1);
        if (logThisFrame) {
            LOGI("stage3.3c: enqueue tag=real-bypass elapsed=%lldms",
                 static_cast<long long>(enqueueMs));
        }
        if (logThisFrame || totalMs >= kSlowFrameMs) {
            LOGI("stage3.3e: dispatch total count=%llu bypass=1 total=%lldms "
                 "fence=%lldms enqueue=%lldms inputEma=%.1f",
                 static_cast<unsigned long long>(g_context->dispatchCount),
                 static_cast<long long>(totalMs),
                 static_cast<long long>(waitFenceMs),
                 static_cast<long long>(enqueueMs),
                 static_cast<double>(g_adaptive.inputFpsEma));
        }
        return true;
    }

    try {
        std::vector<int> noOutSems;
        const auto p0 = std::chrono::steady_clock::now();
        LSFG_3_1::presentContext(g_context->contextId, -1, noOutSems);
        const auto p1 = std::chrono::steady_clock::now();
        LSFG_3_1::waitIdle();
        const auto p2 = std::chrono::steady_clock::now();
        lsfgPresentMs = elapsedMs(p0, p1);
        lsfgWaitIdleMs = elapsedMs(p1, p2);
        if (logThisFrame) {
            LOGI("stage3.3b: LSFG present=%lldms waitIdle=%lldms ctx=%d slot=%u",
                 static_cast<long long>(lsfgPresentMs),
                 static_cast<long long>(lsfgWaitIdleMs),
                 g_context->contextId, slot);
        }
        g_context->pingPongIndex = (g_context->pingPongIndex + 1U) & 1U;
        g_context->lsfgPresentCount += 1;
        recordLsfgCostLocked(lsfgWaitIdleMs, logThisFrame);
    } catch (const std::exception& e) {
        LOGE("stage3.3b: LSFG presentContext threw: %s", e.what());
        return false;
    }

    const bool hasValidInterp = g_context->lsfgPresentCount > 1 && !g_context->outputs.empty();
    const bool showInterp = hasValidInterp && !cadenceSuppressInterp;
    const uint32_t cadenceSuppressBefore = g_adaptive.cadenceSuppressFrames;
    if (hasValidInterp && cadenceSuppressInterp &&
        (cadenceBreak || cadenceSuppressBefore == 1 || logThisFrame)) {
        LOGI("stage3.3d: cadence recovery real-only break=%d suppress=%u",
             static_cast<int>(cadenceBreak),
             cadenceSuppressBefore);
    }
    if (g_adaptive.cadenceSuppressFrames > 0) {
        g_adaptive.cadenceSuppressFrames -= 1;
    }
    int64_t upscaleWaitMs = -1;
    AHardwareBuffer* interpAhb = !g_context->outputs.empty() ? g_context->outputs[0].get() : nullptr;
    bool interpUpscaled = false;
    if (showInterp && upscaleInterpLocked(logThisFrame, &upscaleWaitMs)) {
        interpAhb = g_context->interpOutput.get();
        interpUpscaled = true;
    }
    const auto b0 = std::chrono::steady_clock::now();
    if (showInterp) {
        const int32_t presentMode = g_presentMode.load(std::memory_order_acquire);
        if (presentMode == 1) {
            enqueueAhbToPresenterLocked(realAhb, "real", g_context->dispatchCount, 0);
            enqueueAhbToPresenterLocked(interpAhb, interpUpscaled ? "interp-up" : "interp", g_context->dispatchCount, 1);
        } else {
            enqueueAhbToPresenterLocked(interpAhb, interpUpscaled ? "interp-up" : "interp", g_context->dispatchCount, 0);
            enqueueAhbToPresenterLocked(realAhb, "real", g_context->dispatchCount, 1);
        }
    } else {
        enqueueAhbToPresenterLocked(realAhb, "real", g_context->dispatchCount, 0);
    }
    const auto b1 = std::chrono::steady_clock::now();
    const int64_t enqueueMs = elapsedMs(b0, b1);
    const int64_t totalMs = elapsedMs(frameStart, b1);
    if (logThisFrame) {
        const char* enqueueTag = "real";
        if (showInterp) {
            const int32_t presentMode = g_presentMode.load(std::memory_order_acquire);
            enqueueTag = presentMode == 1 ? "real+interp" : "interp+real";
        } else if (hasValidInterp && cadenceSuppressInterp) {
            enqueueTag = cadenceBreak ? "real-cadence" : "real-recover";
        }
        LOGI("stage3.3c: enqueue tag=%s elapsed=%lldms",
             enqueueTag,
             static_cast<long long>(enqueueMs));
    }
    if (logThisFrame || totalMs >= kSlowFrameMs) {
        LOGI("stage3.3e: dispatch total count=%llu bypass=0 validInterp=%d showInterp=%d cadenceBreak=%d "
             "cadenceSuppress=%u total=%lldms "
             "fence=%lldms lsfgPresent=%lldms lsfgWaitIdle=%lldms upscale=%lldms enqueue=%lldms inputEma=%.1f",
             static_cast<unsigned long long>(g_context->dispatchCount),
             static_cast<int>(hasValidInterp),
             static_cast<int>(showInterp),
             static_cast<int>(cadenceBreak),
             cadenceSuppressBefore,
             static_cast<long long>(totalMs),
             static_cast<long long>(waitFenceMs),
             static_cast<long long>(lsfgPresentMs),
             static_cast<long long>(lsfgWaitIdleMs),
             static_cast<long long>(upscaleWaitMs),
             static_cast<long long>(enqueueMs),
             static_cast<double>(g_adaptive.inputFpsEma));
    }
    return true;
}

bool probeImportDecoderAhb(AHardwareBuffer* decoderAhb, int64_t timestampNs, float observedInputFps) {
    if (decoderAhb == nullptr) {
        return false;
    }
    if (g_contextBootState.load(std::memory_order_acquire) != ContextBootState::kReady) {
        return false;
    }
    const auto probeStart = std::chrono::steady_clock::now();
    std::lock_guard<std::mutex> lock(g_contextMutex);
    if (g_vk == nullptr || g_vk->device == VK_NULL_HANDLE) {
        return false;
    }

    static uint64_t s_importCount = 0;
    ++s_importCount;
    const bool logImport = (s_importCount == 1) || (s_importCount % 60 == 0);

    DecoderAhbImport* cachedImport = findDecoderImportLocked(decoderAhb, s_importCount);
    if (cachedImport != nullptr && g_context != nullptr && g_context->pipeline != VK_NULL_HANDLE) {
        if (logImport) {
            LOGI("stage3.3a-ii: decoder AHB cache hit key=%p externalFormat=0x%llx cache=%zu",
                 cachedImport->key,
                 static_cast<unsigned long long>(cachedImport->externalFormat),
                 g_context->decoderImports.size());
        }
        const bool dispatchOk = dispatchYuvToRgbaLocked(
            *cachedImport,
            timestampNs,
            observedInputFps);
        const int64_t totalMs = elapsedMs(probeStart, std::chrono::steady_clock::now());
        if (logImport || totalMs >= kSlowFrameMs) {
            LOGI("stage3.3e: pipeline total source=cache-hit count=%llu total=%lldms dispatchOk=%d cache=%zu",
                 static_cast<unsigned long long>(s_importCount),
                 static_cast<long long>(totalMs),
                 static_cast<int>(dispatchOk),
                 g_context != nullptr ? g_context->decoderImports.size() : 0);
        }
        return dispatchOk;
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

    const bool pipelineReady = ensureYcbcrPipelineLocked(formatProps);
    if (!pipelineReady || g_context == nullptr || g_context->pipeline == VK_NULL_HANDLE) {
        return true;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(decoderAhb, &desc);

    const auto importStart = std::chrono::steady_clock::now();
    cachedImport = createDecoderImportLocked(
        decoderAhb,
        formatProps,
        ahbProps,
        desc,
        s_importCount,
        logImport);
    const auto importEnd = std::chrono::steady_clock::now();
    if (cachedImport == nullptr) {
        return false;
    }
    if (logImport) {
        LOGI("stage3.3a-ii: decoder AHB cache miss importElapsed=%lldms key=%p",
             static_cast<long long>(
                 std::chrono::duration_cast<std::chrono::milliseconds>(importEnd - importStart).count()),
             cachedImport->key);
    }
    const bool dispatchOk = dispatchYuvToRgbaLocked(
        *cachedImport,
        timestampNs,
        observedInputFps);
    const int64_t totalMs = elapsedMs(probeStart, std::chrono::steady_clock::now());
    if (logImport || totalMs >= kSlowFrameMs) {
        LOGI("stage3.3e: pipeline total source=cache-miss count=%llu total=%lldms import=%lldms dispatchOk=%d cache=%zu",
             static_cast<unsigned long long>(s_importCount),
             static_cast<long long>(totalMs),
             static_cast<long long>(elapsedMs(importStart, importEnd)),
             static_cast<int>(dispatchOk),
             g_context != nullptr ? g_context->decoderImports.size() : 0);
    }
    return dispatchOk;
}

bool probeImportDecoderAhbLegacy(AHardwareBuffer* decoderAhb, int64_t timestampNs, float observedInputFps) {
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
            DecoderAhbImport transientImport{};
            transientImport.image = image;
            transientImport.view = decoderView;
            dispatchOk = dispatchYuvToRgbaLocked(transientImport, timestampNs, observedInputFps);
            vkDestroyImageView(g_vk->device, decoderView, nullptr);
        }
    }

    vkFreeMemory(g_vk->device, memory, nullptr);
    vkDestroyImage(g_vk->device, image, nullptr);
    return dispatchOk || !pipelineReady;
}

} // namespace FramegenPipeline
