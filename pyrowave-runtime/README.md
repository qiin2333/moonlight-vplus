# PyroWave Android runtime

This build-only Android library module compiles the pinned
`third-party/pyrowave` submodule through CMake. The Android Gradle Plugin
builds and packages `libpyrowave-shared.so` for arm64-v8a and armeabi-v7a.
It adds no UI, service, permission, or decoder API.

Initialize recursive submodules, configure the existing audio-haptics SDK
dependency, then build the app normally. No PowerShell script, source copy,
or prebuilt library under `app/src/main/jniLibs` is required. Native build
outputs remain under this module's `build` and `.cxx` directories.

The runtime keeps API 22 compatibility and is separate from the arm64/API 29
frame-generation module. Enabling `enableX86TestAbi` also builds the app's
x86 test ABI. PyroWave availability still depends on runtime Vulkan/device
capabilities; packaging the library does not enable it on unsupported devices.

## Integration documents

- [Client contract](../docs/pyrowave-client-contract.md)
- [Decoder design](../docs/pyrowave-decoder-design.md)
- [Vulkan synchronization](../docs/pyrowave-vulkan-synchronization-design.md)
