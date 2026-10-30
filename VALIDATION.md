# Historical validation report · 0.4.2 · 2026-09-06

> 本文件保留旧版本的原始验收记录，不代表 0.5.0 当前状态。0.5.0 已连接 SM8750 真机，
> 使用 Kokoro、OpenCL 与公开 Hexagon 运行库；当前证据见本次交付的 0.5.0 验证记录及
> `docs/NPU_HEXAGON_0.4.4.md`。以下“未连接设备 / NPU 未集成 / Piper”等均属于历史结论。

当前报告适用于 Snapdragon 8 Elite / Android 16 问题的代码修复。没有连接 Android 真机，未复现用户设备的 tombstone/logcat。

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --offline` 全部成功。
- 应用单元测试 19/19 通过；NPU 纯路径和 UTF-8 主机测试另 4/4 通过。
- Lint：0 错误、17 警告。警告包括旧 targetSdk、库版本、资源与偏好写入建议；未提升 targetSdk 或引入无关升级。
- Debug APK：com.leap8.offline，versionCode 6，versionName 0.4.2，arm64-v8a，minSdk 26，targetSdk 35。
- APK：148,110,740 bytes；SHA-256 `3266d51c8574632afa65d0532f5cfe59fce855fd2a594fb95f0587832be19828`；标准 Android debug 证书签名，v2 验证通过。
- 19 个 ARM64 ELF 的 PT_LOAD 全部至少 16 KiB 对齐；105 项 DT_NEEDED 依赖全部解析；zipalign 4-byte / 16 KiB 检查通过。
- JNI 已通过 NDK 29 编译并进入 APK；Genie 用 Qualcomm 官方公开头文件通过 NDK 语法检查，未链接专有库。
- NPU 可选模块 Gradle `:npu-qairt:tasks` 配置解析通过（使用测试目录/0.0.0 版本占位，只验证脚本配置，不代表拥有 SDK）。
- 设备端真实 Binder/Looper 请求关联回归测试包已编译，未执行。它分别向 CPU/GPU 服务发送 20 次无效模型请求，检查错误回复与 request id，避免加载真实模型。

APK 不含 QAIRT/Genie、SM8750 Context Binary；没有进行实际 HTP、Vulkan 推理、温控/内存压力、连续生成或详情页反复进入退出的手机测试。
源码最终 NPU Manifest 声明 FastRPC，app 保留 DSP Skel 符号；这些 NPU 打包设置需真实 SDK 构建后再次验证。

以下是此前版本的历史记录，不代表当前版本已获得真机证据。

---

# Validation report

Validated on 2026-08-10 in the Codex Windows workspace with Android Studio 2026.1,
JBR 21.0.10, Android SDK 35, Gradle 8.13, NDK 29.0.13113456 and CMake 3.31.6.

## Passed

- `:app:assembleDebug`: successful.
- `:app:testDebugUnitTest`: 13/13 tests passed. This includes four AI-revision parser tests for
  exact before/after locations, `<think>` and fenced output, braces inside JSON strings, truthful
  whole-text fallback and rejection of unstructured responses, three NPU/GPU/CPU fallback and
  memory-headroom tests, a native-process isolation manifest invariant, and the wordbook tests.
- `:app:lintDebug`: 0 errors, 24 non-blocking warnings.
- APK: `com.leap8.offline`, version code 5, version 0.4.1, min SDK 26, target/compile SDK 35,
  arm64-v8a only; size 147,816,900 bytes.
- APK is 4-byte/16 KiB page aligned where applicable and verifies with APK Signature Scheme v2
  using the standard Android debug certificate.
- Manifest contains `INTERNET` only for user-selected API mode and `RECORD_AUDIO` for speaking;
  cleartext traffic is disabled. The non-exported local inference service is declared in the
  separate `:leap8_ai` process. API credential preferences are excluded from backup rules.
- IELTS data asset is stored under its exact `.bin` name and uncompressed in the APK. Its
  SHA-256 is `83665a6aed76a23bbbcf2efbac01959b01b68e1f160187638ef35b55f66b4b43`.
- Piper `en_US-ljspeech-medium` ONNX is stored uncompressed; SHA-256 is
  `6f52a751e2349abe7a76735eb09dc1875298c77ea2342ffd2fef79ff81b87f22`.
- Native audit found 19 arm64 libraries, including Vulkan, seven ARM CPU variants, LLVM
  `libomp.so`, llama JNI, sherpa-onnx and ONNX Runtime. All five JNI entry points are exported;
  all 105 ELF dependency edges resolve to packaged or Android system libraries (0 unresolved).
  The rebuilt llama JNI contains the bounded 24-layer Vulkan offload marker.
- Final debug APK SHA-256:
  `226fdb966ea635cc6930d114e0dbe25b2f1253231df6a2de5c57ed9d36bd8b41`.
- Recommended GGUF remains 2,497,280,736 bytes; SHA-256:
  `2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e`.

## Hardware and service boundary

No Android device was attached to ADB, so the reported device crash could not be reproduced from
a tombstone or logcat. Installation, deliberate worker-process crash recovery, neural audio,
sustained token speed, thermals and 16GB-device memory pressure still require physical-device
verification. At the user's request, only the OpenAI/DeepSeek/Qwen interfaces are retained; no
API keys were configured and no live provider requests were made.

The portable APK now keeps llama.cpp, Vulkan and optional QAIRT outside the UI process. Binder
death is converted to an in-app failure and the next backend is launched in a fresh process; this
contains native/driver process exits that Kotlin exception handling cannot catch. It does not contain
Qualcomm QAIRT/QNN/Genie binaries or an SM8750 Context Binary. The optional `npu-qairt` source,
asset checks, JNI token callbacks and NPU → GPU → CPU failure routing are implemented, but true
Hexagon HTP execution still requires licensed Qualcomm inputs and a Snapdragon 8 Elite test phone.

In-app writing bands and capability estimates are learning diagnostics, not official IELTS results;
production scoring claims require human calibration.
