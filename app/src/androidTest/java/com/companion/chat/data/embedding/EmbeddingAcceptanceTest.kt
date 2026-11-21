package com.companion.chat.data.embedding

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 任务 2.1/2.3 真机验收：真实加载 bge-small-zh-v1.5 ONNX，验证中文语义——
 * 同句两次相似度 > 0.99、语义相近 > 0.85、无关 < 0.5。
 *
 * 这个测试会真正跑 ONNX 推理（仅真机/模拟器可跑），故不能用 JVM 单测替代。
 * 运行：gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...EmbeddingAcceptanceTest
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingAcceptanceTest {

    private val tag = "EmbedAcceptance"

    @Test
    fun real_model_meets_chinese_acceptance_thresholds() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val install = ModelFiles(ctx).ensure()
        assertTrue(
            "模型未就绪: ${(install as? ModelFiles.InstallResult.Missing)?.reason}",
            install is ModelFiles.InstallResult.Ok
        )
        val ready = (install as ModelFiles.InstallResult.Ok).ready

        val raw = OnnxEmbeddingService(
            modelFile = ready.modelFile,
            tokenizer = BertTokenizer(ready.vocab, maxLen = ModelFiles.MAX_TOKENS),
            outputDimensions = ModelFiles.DIMENSIONS,
            pooling = Pooling.CLS
        )
        val svc = SerialEmbeddingService(raw)
        try {
            assertEquals(512, svc.dimensions)

            // 1) 同一句两次：> 0.99
            val a = svc.embed("我明天要面试")
            val a2 = svc.embed("我明天要面试")
            val same = VectorMath.cosineSimilarity(a, a2)
            Log.i(tag, "dim=${a.size} norm=${VectorMath.l2Norm(a)} same=$same")
            assertTrue("同句相似度应 >0.99，实际 $same", same > 0.99f)
            assertTrue("向量应归一化，norm=${VectorMath.l2Norm(a)}", abs(VectorMath.l2Norm(a) - 1f) < 1e-3f)

            // 2) 语义相近：> 0.85（验收例）
            val near = VectorMath.cosineSimilarity(a, svc.embed("明天去公司面试"))
            Log.i(tag, "paraphrase=$near")
            assertTrue("近义句子相似度应 >0.85，实际 $near", near > 0.85f)

            // 3) 无关：< 0.5
            val far = VectorMath.cosineSimilarity(a, svc.embed("我对花生过敏"))
            Log.i(tag, "unrelated=$far")
            assertTrue("无关句子相似度应 <0.5，实际 $far", far < 0.5f)
        } finally {
            runCatching { raw.close() }
        }
    }
}
