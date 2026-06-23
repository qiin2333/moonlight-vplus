/**
 * Android x86 fallback for nanors Reed-Solomon bindings.
 *
 * The upstream x86 wrapper uses Clang target multiversioning for SSSE3/AVX
 * variants. Recent Android NDK bionic headers expose declarations that do not
 * mix with those target attributes, so emulator-only x86 builds use the
 * default implementation.
 */

#ifdef _FORTIFY_SOURCE
  #undef _FORTIFY_SOURCE
#endif

#ifndef NDEBUG
  #define NDEBUG
#endif

#define DECORATE_FUNC_I(a, b) a##b
#define DECORATE_FUNC(a, b) DECORATE_FUNC_I(a, b)

#define reed_solomon_init DECORATE_FUNC(reed_solomon_init, ISA_SUFFIX)
#define reed_solomon_new DECORATE_FUNC(reed_solomon_new, ISA_SUFFIX)
#define reed_solomon_new_static DECORATE_FUNC(reed_solomon_new_static, ISA_SUFFIX)
#define reed_solomon_release DECORATE_FUNC(reed_solomon_release, ISA_SUFFIX)
#define reed_solomon_decode DECORATE_FUNC(reed_solomon_decode, ISA_SUFFIX)
#define reed_solomon_encode DECORATE_FUNC(reed_solomon_encode, ISA_SUFFIX)

#define obl_axpy_ref DECORATE_FUNC(obl_axpy_ref, ISA_SUFFIX)
#define obl_scal_ref DECORATE_FUNC(obl_scal_ref, ISA_SUFFIX)
#define obl_axpyb32_ref DECORATE_FUNC(obl_axpyb32_ref, ISA_SUFFIX)
#define obl_axpy DECORATE_FUNC(obl_axpy, ISA_SUFFIX)
#define obl_scal DECORATE_FUNC(obl_scal, ISA_SUFFIX)
#define obl_swap DECORATE_FUNC(obl_swap, ISA_SUFFIX)
#define obl_axpyb32 DECORATE_FUNC(obl_axpyb32, ISA_SUFFIX)
#define axpy DECORATE_FUNC(axpy, ISA_SUFFIX)
#define scal DECORATE_FUNC(scal, ISA_SUFFIX)
#define gemm DECORATE_FUNC(gemm, ISA_SUFFIX)
#define invert_mat DECORATE_FUNC(invert_mat, ISA_SUFFIX)

#define ISA_SUFFIX _def
#include "moonlight-common-c/nanors/deps/obl/autoshim.h"
#include "moonlight-common-c/nanors/rs.c"
#undef ISA_SUFFIX

#undef reed_solomon_init
#undef reed_solomon_new
#undef reed_solomon_new_static
#undef reed_solomon_release
#undef reed_solomon_decode
#undef reed_solomon_encode

#include "moonlight-common-c/src/rswrapper.h"

reed_solomon_new_t reed_solomon_new_fn;
reed_solomon_release_t reed_solomon_release_fn;
reed_solomon_encode_t reed_solomon_encode_fn;
reed_solomon_decode_t reed_solomon_decode_fn;

void reed_solomon_init(void) {
  reed_solomon_new_fn = reed_solomon_new_def;
  reed_solomon_release_fn = reed_solomon_release_def;
  reed_solomon_encode_fn = reed_solomon_encode_def;
  reed_solomon_decode_fn = reed_solomon_decode_def;
  reed_solomon_init_def();
}
