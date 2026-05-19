# :framegen native 顶层 CMake。
#
# 阶段 1：只把上游 lsfg-vk-framegen 静态库 + volk 拉进来，编一个空 JNI 桥（jni_bridge.cpp）
#         链成 libmoonlight-framegen.so，证明链路通了。
#
# 不包含：
#   - liblsfg-vk.so（Linux Vulkan layer，Android 不用）
#   - shader 提取链路（pe-parse、dxbc）—— 后续阶段加
#   - 真实的 framegen JNI 实现 —— 后续阶段加

cmake_minimum_required(VERSION 3.22.1)

project(moonlight-framegen
    DESCRIPTION "Moonlight Android — Lossless Scaling frame generation bridge"
    LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# 上游 lsfg-vk-android 子模块根目录。
set(LSFGVK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/lsfg-vk-android")

# ----- volk（Vulkan loader）-----
# 上游 LSFG-Android-Application 的做法：绕过 thirdparty/volk/CMakeLists.txt（带 install 规则
# 干扰 AGP），直接编译两文件库本身并打开 VK_USE_PLATFORM_ANDROID_KHR=1。
# 这个 define 必须 PUBLIC，因为 framegen 的 image.cpp 等使用 AHB Vulkan 结构体时也要看到它。
add_library(volk STATIC "${LSFGVK_ROOT}/thirdparty/volk/volk.c")
target_include_directories(volk PUBLIC "${LSFGVK_ROOT}/thirdparty/volk")
target_compile_definitions(volk PUBLIC VK_USE_PLATFORM_ANDROID_KHR=1)

# ----- 上游 framegen 核心 -----
# framegen/CMakeLists.txt 把 lsfg-vk-framegen 定义为 STATIC，链接 volk。
# 上游用 CMAKE_CONFIGURE_DEPENDS 让 AGP 增量构建能感知到子模块内的源文件变化——照搬。
file(GLOB_RECURSE FRAMEGEN_WATCH_SOURCES CONFIGURE_DEPENDS
    "${LSFGVK_ROOT}/framegen/src/*.cpp"
    "${LSFGVK_ROOT}/framegen/src/*.hpp"
    "${LSFGVK_ROOT}/framegen/include/*.hpp"
    "${LSFGVK_ROOT}/framegen/public/*.hpp"
    "${LSFGVK_ROOT}/framegen/v3.1_src/*.cpp"
    "${LSFGVK_ROOT}/framegen/v3.1p_src/*.cpp")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    ${FRAMEGEN_WATCH_SOURCES})
add_subdirectory("${LSFGVK_ROOT}/framegen" framegen_build EXCLUDE_FROM_ALL)

# ----- 我们自己的 JNI 桥 -----
add_library(moonlight-framegen SHARED
    jni_bridge.cpp)

target_include_directories(moonlight-framegen PRIVATE
    "${LSFGVK_ROOT}/framegen/public"   # LSFG_3_1 / LSFG_3_1P 公共 API
    "${LSFGVK_ROOT}/framegen/include") # Core::Image 等内部头（后续 AHB 路径要用）

# Android 系统库：android（AHardwareBuffer）、log、nativewindow（API 26+ HardwareBuffer JNI）
find_library(android-lib   android)
find_library(log-lib       log)
find_library(nativewindow-lib nativewindow)

target_link_libraries(moonlight-framegen PRIVATE
    lsfg-vk-framegen
    volk
    ${android-lib}
    ${log-lib}
    ${nativewindow-lib})

# 隐藏符号默认；只导出 JNI_OnLoad + Java_* 入口（在 jni_bridge.cpp 里显式 JNIEXPORT）。
set_target_properties(moonlight-framegen PROPERTIES
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON)
