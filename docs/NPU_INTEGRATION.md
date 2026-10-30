# Snapdragon NPU 接入与验收

## 新增：公开 llama.cpp Hexagon 路线

已在 SM8750 真机通过官方 b11982 Snapdragon Android 运行库直接执行现有 Qwen3-4B Q4_K_M GGUF，
无需 QAIRT SDK、Genie Context Binary 或重新下载模型。真实 v79 CDSP 会话、HTP 算子和 37/37 层卸载
已经实测，详见 [Hexagon 接入与证据](NPU_HEXAGON_0.4.4.md)。设置 `HEXAGON_RUNTIME` 启用此构建。
这条路线保持 b10333 GPU/CPU 核心不变，在 NPU 独立进程先加载隔离的 b11982 运行库。

以下内容仅适用于仍保留的可选 QAIRT/Genie 路线，不是 Hexagon GGUF 路线的前置条件。

## 可选 QAIRT/Genie 路线

便携 APK 包含 llama.cpp GPU/CPU 后端，不包含 Qualcomm QAIRT、Genie 或专用 Context Binary。
GGUF 不能直接作为 Genie HTP 模型使用。NPU 路由只在元数据检查通过后列为“待验证”；
只有实际生成成功才报告 Hexagon NPU。当前主应用不会在界面进程加载原生推理库。

## 必需输入

- SM8750 / Snapdragon 8 Elite（HTP v79），或 SM8650（HTP v75）；Android 15 / API 35 及以上。
  用户报告的 Android 16 满足版本门槛，仍须匹配设备 firmware、QAIRT 与模型。
- 合法取得的 QAIRT SDK，包含 Genie 头文件、ARM64 库、对应 Stub 和 DSP Skel。
- 使用同一 QAIRT 版本、目标 SoC 编译的 ChatML 模型 Context Binary 与 tokenizer。

把完整模型包放入 `npu-qairt/src/main/assets/models/essay/`：

```text
metadata.json
genie_config.json
tokenizer.json
<genie_config 中每一个 ctx-bins 文件，允许安全的相对子目录>
```

`metadata.json` 模板（把 QAIRT 版本、资产哈希替换成真实值；每个 `ctx-bins` 路径都要列出）：

```json
{
  "soc_model": "SM8750",
  "qairt_version": "实际QAIRT版本",
  "prompt_format": "chatml",
  "file_sha256": {
    "genie_config.json": "对应文件的64位十六进制SHA-256",
    "tokenizer.json": "对应文件的64位十六进制SHA-256",
    "model/context_0.bin": "对应文件的64位十六进制SHA-256"
  }
}
```

`genie_config.json` 必须有 `dialog.engine.backend.type = QnnHtp`、
`dialog.engine.model.type = binary`、非空 `binary.ctx-bins`、有效 context 和 tokenizer 配置。
构建检查缺失的文件和库及每个 SHA-256；运行时再次验证 SoC、SDK、HTP 配置与提取后的资产，拒绝不匹配的模型。

## 构建

设置 `QAIRT_PATH` 为真实 SDK 目录；若目录名不是完整版本号，另设置 `QAIRT_VERSION`。
例如 SDK 完整版本是 `2.XX.Y.Z` 时，必须填实际数字，不能把占位文本当作版本。

```powershell
$env:QAIRT_PATH = "D:\Qualcomm\QAIRT\<完整版本号>"
$env:QAIRT_VERSION = "<完整版本号>"
.\gradlew.bat :app:assembleDebug
```

启用该模块时 app 最低 SDK 自动升为 35，避免与可选库的 manifest 冲突。
NDK 默认 `29.0.13113456`，CMake 默认 `3.31.6`，可通过 `QAIRT_NDK_VERSION` /
`QAIRT_CMAKE_VERSION` 与真实 SDK 要求对齐。不得把不兼容工具链构建成功当成执行兼容证明。

Android 12+ 需要在 NPU 模块 Manifest 声明设备提供的 `libcdsprpc.so`；不把厂商系统库复制进 APK。
`ADSP_LIBRARY_PATH` 包含提取后的 nativeLibraryDir 与系统 DSP 目录；app 打包保留 Skel 符号，
避免用 ARM strip 处理 Hexagon ELF。模型资产按应用更新时间提取，使用临时文件完成安装。

## 运行与回退

NPU、GPU、CPU 分别运行在 `:leap8_npu`、`:leap8_gpu`、`:leap8_cpu`，语音在 `:leap8_speech`。
服务在后台生成前复制 Android Message；界面通过 request id 关联结果和进度。
后端进程退出时通知请求失败，解绑后终止旧进程，再按 NPU → GPU → CPU 继续。
低内存预检失败不永久禁用 GPU；CPU native 崩溃后本会话停止继续尝试它。

Genie 使用 `dialog.max-num-tokens` 限制真实生成长度，每次 query 前重置 dialog。
UTF-8 token 流保留跨 token 的中文/emoji 字节，避免 JNI modified UTF-8 引起损坏。

## 真机验收

1. 核对 `adb shell getprop ro.soc.model`、Android 版本及 `adb shell getconf PAGE_SIZE`。
2. 飞行模式下打开词汇、听力、口语、写作详情页；重复进入退出并快速开始/停止发音。
3. 导入推荐 GGUF，运行英文、中文和 emoji 输入；记录实际 backend、首 token、峰值内存与连续稳定性。
4. NPU 版通过 Genie/QNN 日志确认 HTP 执行；仅看界面名称或耗时不足以证明 NPU 可用。
5. 用单独构建的缺失/不匹配模型包测试候选拒绝；用调试环境中的工作进程退出验证回退与编辑内容保留。

Genie 路线仍没有专有 QAIRT SDK 和匹配的 Genie Context Binary；这不影响上述 Hexagon GGUF 路线。
0.4.3 便携版已在 SM8750 真机通过 Adreno OpenCL GPU、严格 CPU、GPU 进程故障后自动 CPU 回退测试。
该设备的 Vulkan Q4_K 计算管线创建失败，SM8750 已改用 OpenCL；其他设备保留 Vulkan 候选。
上述“需要 SDK / Context Binary”的限制只针对 Genie 方案。公开 Hexagon GGUF 方案不需要这些专有输入；
其 CLI HTP 证据、APK 验收边界和构建方式见 `NPU_HEXAGON_0.4.4.md`。`DEVICE_VALIDATION_0.4.3.md` 是旧版历史记录。

参考：[Android 厂商原生库声明](https://developer.android.com/guide/topics/manifest/uses-native-library-element)、
[Qualcomm Genie 官方头文件](https://github.com/qualcomm/qidk/blob/master/GenAI-Solutions/AI-Assistant/app/src/main/cpp/genie/GenieDialog.h)。
