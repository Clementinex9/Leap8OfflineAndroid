# 0.4.3 真机调试记录

验收日期：2026-10-29 至 2026-10-30（Asia/Taipei）。

## 设备与构建

- vivo V2520A，SM8750 / Snapdragon 8 Elite，Adreno 830，arm64-v8a，API 37，约 15 GB 系统可见 RAM。
- versionCode 7 / versionName 0.4.3。使用原 debug 签名覆盖安装，未卸载主应用或清除数据；原有 3/5048 学词记录保留。
- PowerShell 7.6.5、Gradle 8.13、JDK 21.0.11、NDK 29、CMake 3.31.6。
- 推荐模型已通过 App 的文件选择器导入私有存储：Qwen3-4B-Instruct-2507 Q4_K_M，2,497,280,736 字节。
- GGUF SHA-256：`2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e`。

## 已完成验证

| 验证 | 结果 |
| --- | --- |
| 记忆工坊、总词本、精听、口语、阅读、写作、AI 精修、AI 教练、语法、模考 | 连续两轮进入/退出，主进程 PID 6434 保持不变 |
| 今日、路径、练习、进度、我的 | 均成功进入，无主进程重启 |
| JVM 单元测试 | 19 项通过，0 失败 |
| 真机 instrumentation | 6 项通过，0 失败；最终一轮 `OK (6 tests)`，约 50.6 秒 |
| Piper 发音 | INIT 返回 READY，实际 hello 合成播放返回 PLAYED，主进程存活 |
| 强制 CPU 推理 | 有效文本、ARM CPU backend、进度处理器和结果一致 |
| 强制 GPU 推理 | 有效文本、OpenCL backend、进度处理器和结果一致 |
| GPU 进程死亡 | 模型加载后主动结束 GPU worker，同一请求自动切换严格 CPU 并返回有效文本，主进程不变 |
| Binder/Looper 回归 | CPU、GPU 各连续 20 个请求均保留 request id 和错误回复，无 Message 回收造成的丢失 |

OpenCL 的硬件证据不是 Android feature flag：日志识别 `QUALCOMM Adreno(TM) 830 (OpenCL 3.0 Adreno(TM) 830)`，
报告 `offloaded 24/37 layers to GPU`、`OpenCL model buffer size = 1624.52 MiB`，随后实际生成 15 tokens 成功。
首次短任务约 20.9 秒，包含模型装载及首次 kernel 编译，不能当作稳定吞吐基准。
CPU 短任务约 5.4 秒；强制 GPU 退出后的 CPU 回退任务约 4.8 秒。

应用界面也已实际完成一次中文纠错：给出诊断、规则、`He goes to school every day.` 修正版和迁移练习。
该次 CPU 生成 162 tokens，约 18.7 秒；主进程 PID 26703 未变。

最终应用界面的 AI 精修也通过 OpenCL 完成：输入 `HE GO TO SCHOOL EVERY DAYS`，
显示完整修正版 `He goes to school every day.`、修正前后对比、中文理由及真实 OpenCL 引擎信息。
该次 GPU 生成 140 tokens，约 31.8 秒，主进程 PID 31490 保持不变。
截图位于交付目录 `Leap8-0.4.3-AI-revision.png`。

## 修复依据

1. 原 Piper ONNX 缺少 sherpa-onnx 要求的 metadata，加载失败；已只补顶层 metadata，模型图 SHA-256 保持不变。
   转换工具：`tools/prepare_piper_model.py`。Piper 只在首次播放时装载，并由独立进程持有 native 对象。
2. 所有 native AI 后端分进程，Binder death、超时、错误会回到调用端，再启用下一个独立后端。
   CPU-only 不枚举/装载 Vulkan 或 OpenCL，防止 CPU 回退继承损坏的 GPU 驱动状态。
3. Android Message 在离开主 Looper 前复制；结束请求后解绑、终止 worker，等待进程退出。
   绑定阶段遇到旧 ServiceRecord 仅允许一次有限重绑；已发出的生成任务不重复执行。
4. SM8750 的 Vulkan 驱动在真实 Q4_K decode 时返回 `createComputePipeline: ErrorUnknown`。
   最终改用设备公开的 OpenCL 驱动，编译并打包 ggml OpenCL backend；没有打包任何手机厂商驱动文件。
   构建过程和许可证见 `OPENCL_ANDROID_BUILD.md` 与 `tools/build-opencl-android.ps1`。
5. 空列表、持久化题号和切题后的选项越界均加入防护；页面加载和存储异常不会直接终止界面。
6. 硬件候选与成功记录分开显示；更换模型后重新验证。生成期间显示实际尝试的处理器，结果记录实际 backend。

## 明确未完成的硬件能力

当前安装包没有匹配 SM8750 的 QAIRT/Genie SDK 及 Context Binary，因此 NPU 未激活，也没有声称 NPU 推理通过。
NPU 模块已加入 SoC、配置、文件 SHA-256 与 HTP 目标校验；仍需授权资产和真实 HTP 执行验收。
OpenAI、DeepSeek、Qwen 保留配置接口，本轮没有配置密钥或发起计费 API 请求。

vivo 会冻结后台应用的推理进程，测试期间保持 App 前台；本地生成页面已显示前台运行提示。
本轮为功能和故障恢复验收，不等同于长时间热稳定性、所有输入或所有手机兼容性的保证。
