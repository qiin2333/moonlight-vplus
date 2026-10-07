cmake_minimum_required(VERSION 3.22.1)
project(moonlight-pyrowave-runtime LANGUAGES C CXX)

if(NOT DEFINED PYROWAVE_SOURCE_DIR)
    cmake_path(SET PYROWAVE_SOURCE_DIR NORMALIZE
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third-party/pyrowave")
endif()

foreach(required_file IN ITEMS
        "${PYROWAVE_SOURCE_DIR}/CMakeLists.txt"
        "${PYROWAVE_SOURCE_DIR}/third_party/Granite/CMakeLists.txt"
        "${PYROWAVE_SOURCE_DIR}/third_party/Granite/third_party/volk/volk.h"
        "${PYROWAVE_SOURCE_DIR}/third_party/Granite/third_party/khronos/vulkan-headers/include/vulkan/vulkan.h")
    if(NOT EXISTS "${required_file}")
        message(FATAL_ERROR "PyroWave submodules are missing; run git submodule update --init --recursive third-party/pyrowave")
    endif()
endforeach()

set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(PYROWAVE_DEVEL OFF)
set(PYROWAVE_UTILS OFF)
set(PYROWAVE_BUILD_SHARED ON)
set(PYROWAVE_BUILD_STATIC_C_API OFF)
set(PYROWAVE_BUILD_TESTS OFF)
set(PYROWAVE_INSTALL OFF)
set(GRANITE_INSTALL_TARGETS OFF)
set(GRANITE_INSTALL_EXE_TARGETS OFF)
set(GRANITE_RENDERDOC_CAPTURE OFF)

add_subdirectory("${PYROWAVE_SOURCE_DIR}" pyrowave EXCLUDE_FROM_ALL)
