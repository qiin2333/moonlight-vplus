# 把 SPV_FILE（二进制 SPIR-V）以 uint32_t 数组形式写到 HDR_FILE。
# 由 framegen CMakeLists.txt 的 framegen_compile_shader() 调用。
#
# 输入：
#   SPV_FILE      — 已编译的 .spv 二进制
#   HDR_FILE      — 输出 C 头文件路径
#   SYMBOL_NAME   — 数组符号名（例如 k_yuv_to_rgba_spv）

if(NOT DEFINED SPV_FILE OR NOT DEFINED HDR_FILE OR NOT DEFINED SYMBOL_NAME)
    message(FATAL_ERROR "EmbedSpv.cmake: SPV_FILE/HDR_FILE/SYMBOL_NAME required")
endif()

file(READ "${SPV_FILE}" SPV_HEX HEX)
string(LENGTH "${SPV_HEX}" HEX_LEN)
math(EXPR BYTE_COUNT "${HEX_LEN} / 2")
if(NOT (BYTE_COUNT GREATER 0))
    message(FATAL_ERROR "EmbedSpv.cmake: empty SPV at ${SPV_FILE}")
endif()
math(EXPR REM "${BYTE_COUNT} % 4")
if(NOT (REM EQUAL 0))
    message(FATAL_ERROR "EmbedSpv.cmake: SPV size ${BYTE_COUNT} not multiple of 4")
endif()
math(EXPR WORD_COUNT "${BYTE_COUNT} / 4")

set(BODY "")
set(IDX 0)
while(IDX LESS WORD_COUNT)
    math(EXPR OFFSET "${IDX} * 8")
    string(SUBSTRING "${SPV_HEX}" ${OFFSET} 8 W)
    # little-endian: SPIR-V 文件是小端字流，我们要把每 4 字节翻转
    string(SUBSTRING "${W}" 0 2 B0)
    string(SUBSTRING "${W}" 2 2 B1)
    string(SUBSTRING "${W}" 4 2 B2)
    string(SUBSTRING "${W}" 6 2 B3)
    set(WORD "0x${B3}${B2}${B1}${B0}u")
    if(BODY STREQUAL "")
        set(BODY "${WORD}")
    else()
        set(BODY "${BODY},${WORD}")
    endif()
    math(EXPR IDX "${IDX} + 1")
    # 每 8 个 word 换一行，避免行太长
    math(EXPR LF "${IDX} % 8")
    if(LF EQUAL 0)
        set(BODY "${BODY}\n    ")
    endif()
endwhile()

file(WRITE "${HDR_FILE}"
"// Auto-generated from ${SPV_FILE}\n"
"// DO NOT EDIT.\n"
"#pragma once\n"
"#include <cstdint>\n"
"#include <cstddef>\n"
"\n"
"static constexpr uint32_t ${SYMBOL_NAME}[] = {\n"
"    ${BODY}\n"
"};\n"
"static constexpr size_t ${SYMBOL_NAME}_size = sizeof(${SYMBOL_NAME});\n"
)
