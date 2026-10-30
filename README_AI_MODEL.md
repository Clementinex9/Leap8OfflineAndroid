# Leap 8 本地 AI 模型

推荐模型为 **Qwen3-4B-Instruct-2507 Q4_K_M**。它支持中英双语，基础模型采用 Apache 2.0；
量化文件约 2.33 GiB，适合 16GB RAM 的 arm64 Android 手机。APK 不内嵌多 GB 权重，避免
安装器和应用更新重复搬运，模型只需从本地文件导入一次。

## 安装

1. 按 `model-packs/qwen3-4b-instruct-2507-q4km.json` 的地址取得 GGUF。
2. 校验大小为 `2,497,280,736` 字节，SHA-256 为
   `2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e`。
3. 把文件复制到手机，在“我的 → 离线大模型”中导入。
4. 至少预留约 2.9 GiB 可用空间；导入完成后可断网使用。

App 会流式复制、验证 GGUF 文件头和哈希，并从应用私有目录加载。首次请求显示模型加载与处理器
选择，生成中显示阶段、近似进度、token 数和实际使用的 NPU/GPU/CPU。

## Snapdragon 8 Elite

0.5.0 完整 SM8750 版包含公开的 llama.cpp b11982 Hexagon HTP v79、b10333 OpenCL GPU 与七个 ARM CPU 变体。完整路由是
`Hexagon NPU → Adreno GPU → ARM CPU`；任一后端初始化或生成失败都会在同一请求中回退。

公开 Hexagon 路线直接运行上述 GGUF，不要求 QAIRT/Genie SDK 或专用 Context Binary。
运行库按 SHA-256 校验，并只装入 NPU 独立进程。只有真正 HTP 执行成功才报告 NPU；
缺少匹配运行库、初始化失败、内存不足或子进程退出时切换 GPU/CPU。
ARM CPU 仍负责分词、采样和部分辅助操作，NPU 加速并不意味着 CPU 零占用。
详见 `docs/NPU_HEXAGON_0.4.4.md`；原 Genie 路线的专有资产条件不适用于这条公开路线。

## API 是可选项

OpenAI、DeepSeek、Qwen 是独立可选后端，不影响本地模型。只有用户主动保存自己的 Key 并切换
提供商时，作文或教练文本才会通过 HTTPS 发往该服务；Key 使用 Android Keystore 加密。
