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

# ----- 第三方依赖：DXBC / PE 解析 -----
# 阶段 4：复用 lsfg-vk-android 自带的 dxbc + pe-parse，
# 用于验证 Lossless.dll 中 shader 资源可以在 Android 侧被读取并转译。
add_subdirectory("${LSFGVK_ROOT}/thirdparty/dxbc" dxbc_build EXCLUDE_FROM_ALL)
add_subdirectory("${LSFGVK_ROOT}/thirdparty/pe-parse/pe-parser-library" peparse_build EXCLUDE_FROM_ALL)

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
    jni_bridge.cpp
    framegen_pipeline.cpp
    "${LSFGVK_ROOT}/src/config/config.cpp"
    "${LSFGVK_ROOT}/src/extract/extract.cpp"
    "${LSFGVK_ROOT}/src/extract/trans.cpp")

target_include_directories(moonlight-framegen PRIVATE
    "${LSFGVK_ROOT}"                 # <thirdparty/spirv.hpp>
    "${LSFGVK_ROOT}/include"         # extract/trans.hpp 等
    "${LSFGVK_ROOT}/framegen/public"   # LSFG_3_1 / LSFG_3_1P 公共 API
    "${LSFGVK_ROOT}/framegen/include"  # Core::Image 等内部头（后续 AHB 路径要用）
    "${LSFGVK_ROOT}/thirdparty/toml11/include")

# ----- SPIR-V shaders -----
# 阶段 3.3a-iii：YUV→RGBA compute shader。NDK 自带 glslc，用它把 .comp 编成 .spv，
# 再生成一个 C header（uint32_t 数组），include 到 framegen_pipeline.cpp。
# 这样既不在运行时拉 shaderc 依赖，也不需要打包到 assets。
find_program(GLSLC_EXECUTABLE glslc
    HINTS "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}"
    REQUIRED)
message(STATUS "framegen: glslc = ${GLSLC_EXECUTABLE}")

set(SHADER_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated_shaders")
file(MAKE_DIRECTORY "${SHADER_GEN_DIR}")

function(framegen_compile_shader SHADER_NAME)
    set(SRC "${CMAKE_CURRENT_SOURCE_DIR}/shaders/${SHADER_NAME}.comp")
    set(SPV "${SHADER_GEN_DIR}/${SHADER_NAME}.comp.spv")
    set(HDR "${SHADER_GEN_DIR}/${SHADER_NAME}.comp.spv.h")
    add_custom_command(
        OUTPUT "${HDR}"
        COMMAND "${GLSLC_EXECUTABLE}" --target-env=vulkan1.1 -O
                -o "${SPV}" "${SRC}"
        COMMAND "${CMAKE_COMMAND}" -DSPV_FILE=${SPV} -DHDR_FILE=${HDR}
                -DSYMBOL_NAME=k_${SHADER_NAME}_spv
                -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedSpv.cmake"
        DEPENDS "${SRC}"
                "${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedSpv.cmake"
        COMMENT "Compiling SPIR-V ${SHADER_NAME}"
        VERBATIM)
    target_sources(moonlight-framegen PRIVATE "${HDR}")
endfunction()

framegen_compile_shader(yuv_to_rgba)
framegen_compile_shader(rgba_upscale)

target_include_directories(moonlight-framegen PRIVATE "${SHADER_GEN_DIR}")

# Android 系统库：android（AHardwareBuffer）、log、nativewindow（API 26+ HardwareBuffer JNI）
find_library(android-lib   android)
find_library(log-lib       log)
find_library(nativewindow-lib nativewindow)

target_link_libraries(moonlight-framegen PRIVATE
    lsfg-vk-framegen
    volk
    pe-parse
    dxbc
    ${android-lib}
    ${log-lib}
    ${nativewindow-lib})

# 隐藏符号默认；只导出 JNI_OnLoad + Java_* 入口（在 jni_bridge.cpp 里显式 JNIEXPORT）。
set_target_properties(moonlight-framegen PROPERTIES
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON)
