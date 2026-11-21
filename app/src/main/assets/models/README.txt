本地 embedding 模型目录（任务 2.1；任务 2.3 起改用中文模型）
=============================================================

本目录打包进 APK 的 assets，运行时由 ModelFiles 首次复制到应用私有目录 filesDir/models/。

必需文件：
  bge-small-zh-v1.5.onnx   中文句向量模型（512 维，fp32，约 90MB；量化版可缩到 ~24MB）
  vocab.txt                中文 BERT WordPiece 词表（约 109KB）

来源：BAAI/bge-small-zh-v1.5（经 Xenova 的 ONNX 导出）
  https://huggingface.co/BAAI/bge-small-zh-v1.5
  https://huggingface.co/Xenova/bge-small-zh-v1.5  （onnx/model.onnx、vocab.txt）
许可：MIT

为什么不用 all-MiniLM-L6-v2：它是英文模型，对中文近义/无关句极度重叠（实测「我明天要面试」vs
「明天去公司面试」余弦仅 0.70，而与无关句「我对花生过敏」达 0.60），无法做中文语义去重。
bge-small-zh-v1.5（BERT 架构、CLS 池化）对中文区分清晰，见任务 2.3 验证。

池化：BGE 用 [CLS]（首 token 隐态）+ L2 归一化（代码 Pooling.CLS），不是 mean pooling。

获取方式（二选一）：
  1) 运行 tools/fetch_embedding_model.ps1（走 hf-mirror，自动校验大小/SHA256）；
  2) 手动从上面的 HF 页面下载，放到本目录，文件名保持上面两个。

模型缺失时应用不会崩：EmbeddingServiceFactory 降级 NoopEmbeddingService，语义去重退化为
「完全相同文本」跳过，其它功能照常。
