cmake_minimum_required(VERSION 3.22.1)
project(moonlight_usbip LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(FetchContent)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

# Fixed revisions; fetched sources and license files remain in the build tree.
FetchContent_Declare(usbipdcpp TIMEOUT 600 URL https://codeload.github.com/yunsmall/usbipdcpp/tar.gz/1355113f030c4c13404030e6e7426bceb6085276
    URL_HASH SHA256=0e48cc6fc3ed0d33d7bdda68ee29513faf5b19dcc56250fcde4df12f7f00a9e4)
FetchContent_Declare(libusb TIMEOUT 600 URL https://codeload.github.com/libusb/libusb/tar.gz/refs/tags/v1.0.29
    URL_HASH SHA256=7c2dd39c0b2589236e48c93247c986ae272e27570942b4163cb00a060fcf1b74)
FetchContent_Declare(asio TIMEOUT 600 URL https://codeload.github.com/chriskohlhoff/asio/tar.gz/refs/tags/asio-1-30-2
    URL_HASH SHA256=755bd7f85a4b269c67ae0ea254907c078d408cce8e1a352ad2ed664d233780e8)
FetchContent_Declare(spdlog TIMEOUT 600 URL https://codeload.github.com/gabime/spdlog/tar.gz/refs/tags/v1.15.3
    URL_HASH SHA256=15a04e69c222eb6c01094b5c7ff8a249b36bb22788d72519646fb85feb267e67)
foreach(dep usbipdcpp libusb asio spdlog)
    FetchContent_GetProperties(${dep})
    if(NOT ${dep}_POPULATED)
        FetchContent_Populate(${dep})
    endif()
endforeach()
include(${CMAKE_CURRENT_LIST_DIR}/patch_usbipdcpp.cmake)
moonlight_patch_usbipdcpp(${usbipdcpp_SOURCE_DIR} patched_server_source patched_server_include)

# Mirror libusb's Android source list. Do not discover host pkg-config libraries.
set(usb_sources core descriptor hotplug io sync strerror)
foreach(name IN LISTS usb_sources)
    list(APPEND usb_files ${libusb_SOURCE_DIR}/libusb/${name}.c)
endforeach()
foreach(name linux_usbfs events_posix threads_posix linux_netlink)
    list(APPEND usb_files ${libusb_SOURCE_DIR}/libusb/os/${name}.c)
endforeach()
add_library(usb_android STATIC ${usb_files})
target_include_directories(usb_android PUBLIC ${libusb_SOURCE_DIR}/libusb
    PRIVATE ${libusb_SOURCE_DIR}/android ${libusb_SOURCE_DIR}/libusb/os)
target_link_libraries(usb_android PUBLIC log)
file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/include/libusb-1.0)
configure_file(${libusb_SOURCE_DIR}/libusb/libusb.h
    ${CMAKE_CURRENT_BINARY_DIR}/include/libusb-1.0/libusb.h COPYONLY)

set(SPDLOG_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SPDLOG_BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
add_subdirectory(${spdlog_SOURCE_DIR} ${spdlog_BINARY_DIR} EXCLUDE_FROM_ALL)
file(GLOB core_sources CONFIGURE_DEPENDS
    ${usbipdcpp_SOURCE_DIR}/src/*.cpp
    ${usbipdcpp_SOURCE_DIR}/src/utils/*.cpp
    ${usbipdcpp_SOURCE_DIR}/src/DeviceHandler/*.cpp
    ${usbipdcpp_SOURCE_DIR}/src/InterfaceHandler/*.cpp
    ${usbipdcpp_SOURCE_DIR}/src/LibusbHandler/*.cpp)
list(FILTER core_sources EXCLUDE REGEX "[/\\\\]Server\\.cpp$")
list(APPEND core_sources ${patched_server_source})
add_library(moonlight_usbip SHARED usbip_jni.cpp ${core_sources})
target_include_directories(moonlight_usbip BEFORE PRIVATE ${patched_server_include})
target_include_directories(moonlight_usbip PRIVATE
    ${usbipdcpp_SOURCE_DIR}/include ${asio_SOURCE_DIR}/asio/include
    ${CMAKE_CURRENT_BINARY_DIR}/include)
target_compile_definitions(moonlight_usbip PRIVATE USBIPDCPP_STATIC ASIO_STANDALONE)
target_link_libraries(moonlight_usbip PRIVATE usb_android spdlog::spdlog android log)
target_link_options(moonlight_usbip PRIVATE "-Wl,-z,max-page-size=16384")
