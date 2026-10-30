# Android OpenCL backend build

Run `tools/build-opencl-android.ps1` using PowerShell 7. It builds only the dynamic
`ggml-opencl` target from the existing exact `llama.cpp-b10333` source, using NDK 29,
Android API 26, CMake 3.31.6, and the shared C++ runtime. Its short build directory
defaults to `%TEMP%/leap8-opencl-b10333` to avoid Windows path-length failures.

Dependencies are cached under `work/OpenCL` from the official Khronos repositories:

- `https://github.com/KhronosGroup/OpenCL-Headers`
- `https://github.com/KhronosGroup/OpenCL-CLHPP` (cached for completeness, not needed
  by this backend's C API includes).

The script prints the resolved dependency commits and vendor library SHA-256.
Existing dependency checkouts are reused, not updated implicitly.

The connected Snapdragon 8 Elite / Adreno 830 device exposes
`/vendor/lib64/libOpenCL.so`, declares it in `/vendor/etc/public.libraries.txt`, and
also has `/vendor/lib64/libOpenCL_adreno.so`. The vendor front-end advertises
OpenCL 3.0. A pulled copy of the front-end is retained in
`work/OpenCL/link-only/libOpenCL.so` solely to satisfy the Android linker. Never
package either vendor driver library in the APK.

The staged artifact is `work/OpenCL/build-output/arm64-v8a/libggml-opencl.so`.
This script deliberately does not update `jniLibs`, the manifest, or Kotlin/JNI
selection. Embedded kernels are enabled. Optional Adreno-specialized kernels and
precompiled Qualcomm kernel libraries are disabled for the compatibility build.

An APK targeting API 31+ must declare the publicly exposed vendor library inside
`application`:

```xml
<uses-native-library android:name="libOpenCL.so" android:required="false" />
```

Keep OpenCL loading inside the isolated GPU worker and retain CPU fallback when
the library is missing or the driver fails. Native-device discovery must accept
the `OpenCL` backend rather than hardcoding only `Vulkan`. Configure an app-private
`GGML_OPENCL_KERNEL_CACHE_DIR` before backend initialization for reusable kernel
compilation caches.

Reference: [Android uses-native-library documentation](https://developer.android.com/guide/topics/manifest/uses-native-library-element).
Successful compilation does not establish on-device inference correctness;
loading, kernel compilation, model loading, and generation still need device QA.

## Build verification, 2026-10-30

- Khronos headers commit: `82f60316cc142e7b21dc1d8983b26de18858fbd9`.
- Khronos CLHPP commit: `14f76ab0e180fe1b0e0591d52497ebc88f86ce5e`.
- Build-only vendor link input SHA-256:
  `CD35CF1ECBFB2053E45566421AA605F819B69CEB5264706ED4FEB15BED0942F9`.
- Backend output: 5,841,016 bytes; SHA-256:
  `95E60A944704CC391A95F622DB4BC964D866EA81CC75D962C9B226643687E323`.
- ELF verified as little-endian 64-bit AArch64 shared object. Dynamic dependencies:
  `libggml-base.so`, `libOpenCL.so`, `libm.so`, `libc++_shared.so`, `libdl.so`, `libc.so`.
- All ELF LOAD segments have `0x4000` alignment (16 KiB).
- `ggml_backend_init` and `ggml_backend_opencl_reg` are exported. All 25 required
  `ggml_*` imports resolve against the existing packaged `libggml-base.so` exports.
- `PYTHONUTF8=1` is needed when embedding upstream kernel comments on Chinese
  Windows, where the default Python text encoding would otherwise be GBK.
- No vendor driver was packaged, and no on-device process or UI was changed by
  this build/ELF-verification step.

## Read-only integration review

- The registry name is exactly `OpenCL`; its device type is
  `GGML_BACKEND_DEVICE_TYPE_GPU`, its device name is `GPUOpenCL`, and the hardware
  description is exposed separately. JNI registry matching against `OpenCL` and
  accepting GPU/IGPU therefore selects it correctly.
- Qwen3 is a dense decoder in `src/models/qwen3.cpp`. Its normal matrix products
  use `MUL_MAT`, not the `MUL_MAT_ID` expert routing that needs optional Adreno
  kernels for some quantizations. The generic path includes Q4_K, Q5_K, and Q6_K
  matrix-vector and matrix-matrix kernels even with Adreno kernels disabled;
  this covers the ordinary mixed Q4_K_M weight family. K-quant matrix products
  require F32 activations and contiguous weights/activations; unsupported shapes
  are declined to the CPU scheduler.
- Default F16 key/value caches match the backend's SET_ROWS and copy support.
  Qwen3's RMS normalization, RoPE, SiLU/SwiGLU, attention, and residual add have
  supported OpenCL paths. The K/V precision should remain F16 for first validation.
- `n_gpu_layers=24` is a partial-offload target for the 36-layer Qwen3-4B model;
  output projection and remaining layers stay on CPU. `offload_kqv=true` and
  `op_offload=true` are valid, but add GPU cache/compute memory. OpenCL reports
  global memory with a fixed margin, not reliable live free memory, so the app's
  independent memory check and isolated worker remain important.
- Disabling Adreno-specialized kernels does **not** disable FlashAttention.
  llama.cpp defaults to `flash_attn_type=AUTO`, and the backend supports common
  Qwen head dimensions including 128 with F32 queries and F16 K/V. If the first
  decode fails in FA compilation or execution, disabling FA specifically for
  OpenCL is a useful compatibility diagnostic; ordinary attention can still run.
  Upstream already declines FA on known E17 compiler variants.
- Kernel compilation can terminate the process (`exit(1)`), and `CL_CHECK` errors
  assert. They are not all catchable Java/C++ exceptions. Keep the independent
  GPU worker and binder-death CPU fallback. Cold compilation may take substantially
  longer than subsequent cached launches.
- The app-private kernel cache environment must be set before the first model
  buffer/kernel initialization. The current service does this before generation.
- Backend labels such as `GPU+CPU` prove selection, not completed GPU execution.
  Device QA should capture the loader's actual layer-offload/model-buffer logs
  together with successful generated tokens.

Khronos license assets were copied byte-for-byte to
`app/src/main/assets/third_party/OpenCL-Headers-LICENSE.txt` and
`app/src/main/assets/third_party/OpenCL-CLHPP-LICENSE.txt`. Both source licenses
currently have SHA-256
`3DDF9BE5C28FE27DAD143A5DC76EEA25222AD1DD68934A047064E56ED2FA40C5`.
