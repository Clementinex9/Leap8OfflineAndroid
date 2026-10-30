# SM8750 真正 Hexagon NPU 路线

## 状态与边界

2026-10-30，已连接手机实测：SM8750 / Android API 37 / 4096-byte pages。
使用 ggml-org 官方 b11982 Snapdragon Android ARM64 预编译运行库，以及手机原有
`Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf`，在独立 CLI 完成 32 token 推理。
不使用普通 ARM CPU 推理冒充 NPU；真实执行证据包括 CDSP v79 会话、HTP0 算子和已卸载层。
ARM CPU 仍负责分词、采样和部分辅助操作，所以准确描述为 NPU 加速，不是整机零 CPU 推理。

集成代码通过 JNI 单独 CMake 编译；App/非调试 performance 构建和 Binder 端到端验收由最终集成测试确认。
不能仅凭 CLI 成功声称 APK 集成也已验收。

## 来源与校验

- 官方资产：[llama-b11982-bin-android-arm64-snapdragon.tar.gz](https://github.com/ggml-org/llama.cpp/releases/download/b11982/llama-b11982-bin-android-arm64-snapdragon.tar.gz)
- 官方 release API 的 SHA-256：`7f6b330ad008011af27e30f668b9ee283aa15bd5caf08983cd8f06c5919b8ca7`，下载后已核对通过。
- 大小：63,373,914 bytes。
- [官方 Snapdragon 后端说明](https://github.com/ggml-org/llama.cpp/blob/b11982/docs/backend/snapdragon/README.md)。
- 官方包内含 LICENSE，随 NPU assets 一并保留。
- `work/npu-validation/download-official-runtime.ps1` 使用公开 URL 分段下载、整包哈希校验；没有登录、令牌或模型条款代接受。

## 实测证据

`work/npu-validation/htp-generation-verbose.log` 记录：

```text
ggml-hex: HTP0 hwinfo: threads 6, hvx 6, hmx 1, vtcm 8 MB
ggml-hex: HTP0 new session : session-id 0 domain-id 3 uri file:///libggml-htp-v79.so?...&_dom=cdsp&_session=0
load_tensors: offloaded 37/37 layers to GPU
ggml-hex: HTP0 execute-op MUL_MAT|...|q4_K x f32 -> f32|...|HTP0 x HTP0 -> HTP0
ggml-hex: HTP0 execute-op MUL_MAT|...|q6_K x f32 -> f32|...|HTP0 x HTP0 -> HTP0
```

这里 llama.cpp 的通用日志将所有 offload 设备称作 GPU；选择的实际设备为 `HTP0 (Hexagon)`，不是 Adreno。
32 token 正常输出以 `The opposite of cold is hot...` 开始。另一次 context 4096 测试为 31 个解码步骤
1440.43 ms，约 21.52 token/s；短探测结果不等价于长作文稳定性或准确率承诺。

`work/npu-validation/htp-context4096.log` 保存 context 4096 再次运行结果。
手机文件只写入授权的 `/data/local/tmp/leap8-htp-probe`，未清系统日志或变更主 App 数据。

## 构建与隔离

源码交付包含仅用于 SM8750 构建的必要运行库、完整公开头文件、LICENSE 和校验清单，
位于 `third_party/llama-b11982-android-snapdragon`。`tools/build-sm8750.ps1` 会逐文件验证清单，
自动设置 `HEXAGON_RUNTIME` 并构建非调试 performance APK。也可以手动设置：

```powershell
$env:HEXAGON_RUNTIME = '<项目目录>/third_party/llama-b11982-android-snapdragon'
```

`:npu-qairt` 自动使用 Hexagon 分支，而不是原 Genie 分支。
以下八个文件复制到 `assets/npu-runtime/arm64-v8a/`，生成 SHA-256 清单：
`libggml-base.so`、`libggml-cpu.so`、`libggml-rpc.so`、`libggml-opencl.so`、
`libggml-hexagon.so`、`libggml.so`、`libllama.so`、`libggml-htp-v79.so`。
保留已有 GPU/CPU b10333 JNI 库；新的 ABI 仅在 `:leap8_npu` 进程中加载。

官方包的 `libggml.so` / `libllama.so` 含所有 backend 的 DT_NEEDED，因此 rpc/OpenCL 依赖也必须随新版本隔离。
它们按依赖顺序从校验后、只读、应用私有路径显式加载，不能让 linker 误用 APK 的旧同名核心。
所有新库及新 JNI 经 `llvm-readelf -d` 检查均无 `libc++_shared.so` 依赖，使用静态 C++ runtime。
官方构建为 built-in backend registry，不能调用 `ggml_backend_load` 把它当 DL plugin。
桥只选择 HTP0，并在检测到真正 HTP session 和非零模型层卸载后才允许成功返回 NPU 结果。

`ADSP_LIBRARY_PATH` 包含新 DSP skel 目录和厂商 DSP 路径。
不要将整个 `/vendor/lib64` 加入 `LD_LIBRARY_PATH`：此设备会误解析厂商依赖，导致
系统 `liblog.so` 报 `_ZNSt3__113__hash_memoryEPKvm` 缺失。FastRPC 可通过系统声明的 `libcdsprpc.so` 正常加载。
不复制或修改设备厂商系统库，不依赖 root。

## APK 验收入口

`LocalAiProcessIpcTest` 新增：

- `importedModelGeneratesOnRealHexagonWorker`：导入模型、明确 opt-in、匹配候选后执行实际 NPU 短生成。
- `npuWorkerDeathFallsBackToIndependentGpuOrCpu`：流式输出后通过 App 自有 Binder EXIT 命令仅终止 NPU worker，验证 UI 存活和 GPU/CPU 回退；不依赖 `run-as` 或 debuggable。

仪器参数沿用 `leap8RunModelTests=true`。没有匹配候选时跳过 NPU 测试，不能将跳过视为通过。
现有 CPU/GPU 和回退测试仍需回归；非调试 APK 还需确认 FastRPC namespace、动态加载和 SELinux 行为。
