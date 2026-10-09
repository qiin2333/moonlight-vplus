cmake_minimum_required(VERSION 3.22)
project(pyrowave_dynamic_hdr_tests LANGUAGES CXX)
enable_testing()
add_executable(pyrowave_dynamic_hdr_tests
    pyrowave_dynamic_hdr_test.cpp
    ../../main/cpp/pyrowave_dynamic_hdr.cpp)
target_compile_features(pyrowave_dynamic_hdr_tests PRIVATE cxx_std_17)
target_include_directories(pyrowave_dynamic_hdr_tests PRIVATE
    ../../main/cpp
    ../../../../app/src/main/jni/moonlight-core)
if(MSVC)
    target_compile_options(pyrowave_dynamic_hdr_tests PRIVATE /W4 /WX /utf-8)
else()
    target_compile_options(pyrowave_dynamic_hdr_tests PRIVATE -Wall -Wextra -Werror)
endif()
add_test(NAME pyrowave_dynamic_hdr_tests COMMAND pyrowave_dynamic_hdr_tests)
