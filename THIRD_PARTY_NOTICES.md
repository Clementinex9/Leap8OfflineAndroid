# Third-party notices

## Local AI runtime

- `ggml-org/llama.cpp` b10333, commit `08659901c43b51de735740f1cf61bb82fbe0c4e4`,
  MIT. The license is in `llama-runtime/src/main/cpp/vendor/LICENSE` and inside the APK at
  `assets/third_party/llama.cpp-b10333-LICENSE.txt`.
- SM8750 NPU uses the official ggml-org/llama.cpp **b11982** Snapdragon Android ARM64
  release, MIT. Only the eight necessary runtime libraries are packaged as isolated
  assets; `LICENSE` is retained at `assets/npu-runtime/arm64-v8a/LICENSE`.
  Source delivery keeps those libraries, public headers, license and per-file digests
  in `third_party/llama-b11982-android-snapdragon/`. Archive provenance and digest are
  recorded in its `manifest.json` and `docs/NPU_HEXAGON_0.4.4.md`.
  Device-owned `libcdsprpc.so` and `libOpenCL.so` are not redistributed.
- Khronos OpenCL-Headers and OpenCL-CLHPP notices are retained under
  `assets/third_party/`. b10333 OpenCL uses the phone's public vendor OpenCL library at
  runtime; a pulled link-only input is not part of the APK or source release.
- Khronos Vulkan-Headers v1.3.275, Apache-2.0 OR MIT, and SPIRV-Headers, whose combined
  license file is retained under `assets/third_party/`. They are used to build the Android
  Vulkan backend.
- The seven ARM CPU backends use the Android NDK 29 LLVM OpenMP runtime. `libomp.so` is
  packaged for arm64 and the Android NDK LLVM notice is retained at
  `assets/third_party/Android-NDK-LLVM-NOTICE.txt`.
- The separately delivered Qwen3-4B-Instruct-2507 GGUF derives from the Apache-2.0 Qwen
  base model. Repository, size and hash are recorded in
  `model-packs/qwen3-4b-instruct-2507-q4km.json`.

## Vocabulary

- ECDICT by skywind3000/Linwei, MIT. Leap 8 includes every unique entry carrying the
  `ielts` tag (5,040 rows) plus eight non-overlapping editorial cards. The verbatim ECDICT
  license is packaged at `assets/datasets/ECDICT-LICENSE.txt`.
- ECDICT is an open-source dictionary taxonomy, not an official IELTS vocabulary release.
  IELTS does not publish one canonical exhaustive vocabulary list.

## Offline pronunciation

- sherpa-onnx 1.13.2, Apache-2.0. A verbatim license is packaged at
  `assets/third_party/sherpa-onnx-1.13.2-LICENSE.txt`; AAR SHA-256 is
  `aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245`.
- Kokoro-82M v1.0 weights from `hexgrad/Kokoro-82M`, Apache-2.0, exported for
  sherpa-onnx as `kokoro-int8-multi-lang-v1_0`. The original license is retained with
  the model assets. The APK uses the official October 26, 2026 int8 STFT fix, not the
  earlier ARM-affected int8 export. Model source, archive digest and speaker mapping
  are recorded in `docs/OFFLINE_VOICE.md`.
- Kokoro upstream acknowledges CC BY training audio: Koniwa `tnc` (CC BY 3.0) and
  SIWIS (CC BY 4.0). See <https://huggingface.co/hexgrad/Kokoro-82M#creative-commons-attribution>
  and the model notice packaged in `assets/third_party/kokoro-v1.0-NOTICE.txt`.
- The phonemization data originates from eSpeak NG. Its GPL-3.0 license text is packaged as
  `assets/third_party/espeak-ng-GPL-3.0-COPYING.txt`. A production distributor must review
  the license obligations of every native component in the sherpa-onnx bundle.

## Optional Qualcomm module

This section describes only the retained proprietary Genie alternative, not the public
b11982 Hexagon GGUF route used by the 0.5.0 SM8750 build.

The `npu-qairt` integration follows public API patterns from Qualcomm AI Hub Apps'
`chatapp_android` reference, BSD-3-Clause. QAIRT/QNN/Genie SDK binaries, model weights and
SoC-specific Context Binaries are not included in the portable APK; they have separate terms
that the distributor must accept and verify before packaging.
