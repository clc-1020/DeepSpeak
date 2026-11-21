package com.companion.chat.data.embedding

import android.content.Context
import java.io.File
import java.io.FileNotFoundException

/**
 * 本地 embedding 模型「落盘」管理。任务 2.1
 *
 * 背景：ONNX Runtime 需要一个**真实文件路径**（不能直接读 APK assets 里的流）。
 * 因此首次使用时把 `assets/models/` 下的模型与词表复制到 `filesDir/models/`
 * （应用私有目录：持久化、离线可用），并用 `VERSION` 戳做升级/替换判断。重复调用幂等复用。
 *
 * 支持两种来源：
 * 1) **打包进 APK 的 assets**（默认，离线）：从 assets 拷到 filesDir；
 * 2) **首次启动下载**（v1.1 或用户手动）：下载器把文件交 [installFromFile] 纳入管理。
 * 任一就绪即可。模型缺失时返回 [InstallResult.Missing]，上层降级 [NoopEmbeddingService]，不崩。
 */
class ModelFiles(private val context: Context) {

    /** 已落盘且可用的模型 + 词表（token → id）。 */
    data class Ready(val modelFile: File, val vocab: Map<String, Int>)

    sealed class InstallResult {
        data class Ok(val ready: Ready) : InstallResult()
        data class Missing(val reason: String) : InstallResult()
    }

    private val dir: File get() = File(context.filesDir, "models")
    private val modelFile: File get() = File(dir, MODEL_NAME)
    private val vocabFile: File get() = File(dir, VOCAB_NAME)
    private val versionFile: File get() = File(dir, "VERSION")

    /** 确保模型落盘并可用（幂等）。缺文件时尝试从 assets 拷贝。 */
    fun ensure(): InstallResult {
        return try {
            dir.mkdirs()
            val currentVersion = versionFile.takeIf { it.exists() }?.readText()
            if (modelFile.exists() && vocabFile.exists() && currentVersion == VERSION) {
                return load()
            }
            // 已有文件（手动放置/已下载）但版本戳不符：尝试用 assets 覆盖；assets 没有则沿用现有
            if (modelFile.exists() && vocabFile.exists()) {
                if (copyAssetIfExists(MODEL_ASSET, modelFile) != null) {
                    copyAssetIfExists(VOCAB_ASSET, vocabFile)
                    versionFile.writeText(VERSION)
                }
                return load()
            }
            // 完全没有：从 assets 拷贝
            copyAssetIfExists(MODEL_ASSET, modelFile)
                ?: return InstallResult.Missing("assets 缺少 $MODEL_ASSET（请放入模型文件，或改用首次下载）")
            copyAssetIfExists(VOCAB_ASSET, vocabFile)
                ?: return InstallResult.Missing("assets 缺少 $VOCAB_ASSET")
            versionFile.writeText(VERSION)
            load()
        } catch (e: Exception) {
            InstallResult.Missing("模型落盘失败: ${e.message}")
        }
    }

    /** 供首次下载器 / 用户把已下载文件纳入管理（原子覆盖 + 版本戳）。 */
    fun installFromFile(srcModel: File, srcVocab: File): InstallResult {
        return try {
            dir.mkdirs()
            srcModel.copyTo(modelFile, overwrite = true)
            srcVocab.copyTo(vocabFile, overwrite = true)
            versionFile.writeText(VERSION)
            load()
        } catch (e: Exception) {
            InstallResult.Missing("导入模型失败: ${e.message}")
        }
    }

    private fun load(): InstallResult {
        if (!modelFile.exists() || !vocabFile.exists()) {
            return InstallResult.Missing("模型文件不存在")
        }
        val vocab = HashMap<String, Int>(40000)
        vocabFile.bufferedReader().useLines { lines ->
            var idx = 0
            for (line in lines) {
                vocab[line.trimEnd('\n', '\r')] = idx++
            }
        }
        if (vocab.isEmpty()) return InstallResult.Missing("词表为空")
        return InstallResult.Ok(Ready(modelFile, vocab))
    }

    /** 从 assets 拷贝到 dst（先写 .tmp 再 rename，避免半截文件）；asset 不存在返回 null。 */
    private fun copyAssetIfExists(asset: String, dst: File): File? {
        return try {
            context.assets.open(asset).use { input ->
                val tmp = File(dst.parentFile, dst.name + ".tmp")
                tmp.outputStream().use { output -> input.copyTo(output) }
                if (dst.exists()) dst.delete()
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
            }
            dst
        } catch (e: FileNotFoundException) {
            null
        }
    }

    companion object {
        // 中文场景用 bge-small-zh-v1.5（BERT+WordPiece，512 维）。all-MiniLM-L6-v2 是英文模型，
        // 对中文近义/无关严重重叠无法做语义去重，已于任务 2.3 替换。
        const val MODEL_NAME = "bge-small-zh-v1.5.onnx"
        const val VOCAB_NAME = "vocab.txt"
        const val MODEL_ASSET = "models/bge-small-zh-v1.5.onnx"
        const val VOCAB_ASSET = "models/vocab.txt"
        /** 模型/词表版本戳；换模型时改这里会触发重新拷贝。1=all-MiniLM，2=bge-small-zh。 */
        const val VERSION = "2"
        /** bge-small-zh-v1.5 输出维度。 */
        const val DIMENSIONS = 512
        /** 该模型最大序列长度。 */
        const val MAX_TOKENS = 512
        /**
         * 池化策略（与模型绑定，集中在此便于换模型时只改一处、无需动代码）：
         * BGE 系用 "CLS"（取首 token 隐态）；如需换用 MEAN 句向量的模型改成 "MEAN"。
         */
        const val POOLING_STRATEGY = "CLS"
    }
}
