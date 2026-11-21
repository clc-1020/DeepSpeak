# Sherpa ONNX 本地 AAR 部署说明

由于 Sherpa ONNX 不在 Maven Central，需要手动下载 AAR 文件放到此目录。

## 下载步骤
1. 访问 GitHub releases 页面：
   https://github.com/k2-fsa/sherpa-onnx/releases

2. 下载最新稳定版（推荐 v1.10.42 或更高）的 Android AAR：
   - 文件名示例：sherpa-onnx-1.10.42-android.aar
   - 或根据发布说明选择适合 ARM64/ARMv7/x86_64 的完整版本

3. 将下载的 AAR 文件复制到此目录（app/libs/）

4. Sync Gradle 后即可使用，无需修改 build.gradle 依赖（已配置 fileTree）

## 模型文件
同时需要下载语音识别模型（~100MB），放到 assets/ 或外部存储：
- 推荐模型：sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20
- 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases
- 模型文件包含：encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt

部署后在代码中通过 SherpaOnnxConfig 指定模型路径。
