# EasyTier x86_64 Android test libraries

These libraries exist only to support emulator E2E builds. They are packaged
when Gradle is invoked with `-PenableX8664TestAbi=true`; normal release builds
continue to package the production ARM ABI only.

- EasyTier source: official `v2.6.4` release
- Target: `x86_64-linux-android`, Android API 21
- Android NDK: 28.2
- Rust: 1.95.0
- `libeasytier_android_jni.so` SHA-256:
  `80ECDEBAE94D01D1EC6AF6B14ED02D134F1C94FAB7E45F91F622897483E49A1D`
- `libeasytier_ffi.so` SHA-256:
  `795BA53B2D23D20CD942C30A15CE95E6B6F65607ED55ADF791AC34AAFA0C8F38`

The JNI library is linked with an explicit `DT_NEEDED` dependency on
`libeasytier_ffi.so`, matching Android's namespace-based native loader.
