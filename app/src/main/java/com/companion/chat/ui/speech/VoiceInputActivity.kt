package com.companion.chat.ui.speech

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.companion.chat.CompanionApp
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.speech.AudioRecorder
import com.companion.chat.data.speech.AsrResult
import com.companion.chat.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音识别结果的文本归一（纯函数，可 JVM 单测）。
 *
 * null / 全空白 → null，调用方据此**不写 app_state**，避免空串占着 voice_pending_text
 * 让 MainActivity 反复拉起一个空的待发送内容。非空则 trim 后返回。
 */
internal fun normalizeVoiceText(raw: String?): String? {
    if (raw == null) return null
    val trimmed = raw.trim()
    return trimmed.ifEmpty { null }
}

/**
 * 通知栏「🎙 语音输入」拉起的透明浮层。任务 V2.3.6
 *
 * 交互刻意用**点击开始 / 再点结束**，而不是 ChatScreen 的长按手势：
 * 从通知栏拉起的浮层是单手盲操场景，长按松手容易被判误触（尤其戴手套/走路时），
 * 「点一下开始、再点一下结束」的状态更明确、更容易交互。
 *
 * 取消入口是**返回键**与**长按遮罩**，不是「再点一次」：
 * 再点一次必须走正常收尾（stop → 识别），否则界面写着「再点一次结束」却把结果丢掉，
 * 用户会以为功能坏了（V2.3.6 审查必修项）。
 *
 * 录音 → STT → 写 app_state[voice_pending_text] → 拉起 MainActivity → finish()。
 * 不直接调 ChatViewModel.send()：ViewModel 是 Activity 作用域的，
 * 这里只是一个短命浮层，结果通过 app_state 交给 MainActivity.onResume 读取并删除。
 */
@OptIn(ExperimentalFoundationApi::class)
class VoiceInputActivity : ComponentActivity() {

    private val recorder = AudioRecorder()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** stop() 非幂等（第二次调用会 join 已退出的 worker 并拿到空数组），一次会话只允许一次。 */
    private val stopGuard = AtomicBoolean(false)

    private val recording = mutableStateOf(false)

    /** 取消 / 页面被盖住后置位：后续识别结果一律丢弃，不写 pref、不拉主界面。 */
    @Volatile
    private var cancelled = false

    /** stop / transcribe 进行中：忽略遮罩点击，防止重复会话。 */
    @Volatile
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = applicationContext as CompanionApp

        // 1) 开关检查必须在 setContent 之前：关掉语音输入就别开麦、别出浮层。
        if (!app.sttManager.isEnabled()) {
            Toast.makeText(this, "语音输入已关闭，请到设置里开启", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 2) 只判权限，不在这里弹系统权限框（浮层任务是「一点即用」，弹框会打断链路）。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "没有录音权限，请到设置里开启", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 3) 120s 自动停止：回调在录制线程 → 切 IO 线程再 stop，且只 stop 一次。
        recorder.onAutoStop = {
            ioScope.launch {
                if (cancelled || busy) return@launch
                finishRecording()
            }
        }

        // 4) 返回键 = 取消（丢弃会话，不识别、不写 pref），与 ChatScreen 的「上划取消」语义一致。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancelSession()
        })

        // 5) 全屏遮罩：未录音时点击=开始；录音中点击=**结束并识别**；长按=取消。
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = { onOverlayClick() },
                        onLongClick = { if (recording.value) cancelSession() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (recording.value) {
                        "正在聆听… 再点一次结束（长按或返回键取消）"
                    } else {
                        "点一下开始说话"
                    },
                    color = Color.White,
                )
            }
        }
    }

    private fun onOverlayClick() {
        if (busy || cancelled) return
        if (recording.value) {
            // 录音中点击 = 正常收尾：stop → 识别 → 回填。**不是**取消。
            finishRecording()
        } else {
            stopGuard.set(false)
            recorder.start()
            // AudioRecorder.start() 失败（权限/设备被占用/初始化失败）只置内部标志、不抛异常；
            // 若这里盲目置 true，界面会显示「正在聆听」却什么都没录到，第二次点击只得「没录到声音」。
            recording.value = recorder.isRecording
            if (!recording.value) {
                Toast.makeText(this, "无法开始录音，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 正常收尾：stop() 取完整 WAV 后交给 ASR。
     *
     * 唯一调用点入口有两个：用户再点一次遮罩、120s 自动停回调。两者都只允许 stop 一次，
     * 由 [stopGuard] 保证（[AudioRecorder.stop] 非幂等）。
     */
    private fun finishRecording() {
        if (!stopGuard.compareAndSet(false, true)) return
        busy = true
        ioScope.launch {
            val wav = recorder.stop()
            withContext(Dispatchers.Main) { recording.value = false }
            handleWav(wav)
        }
    }

    /** 取消：释放麦克风后直接退出，结果一律丢弃、不写 pref、不拉主界面。 */
    private fun cancelSession() {
        if (cancelled) return
        cancelled = true
        val shouldStop = stopGuard.compareAndSet(false, true)
        ioScope.launch {
            if (shouldStop) recorder.stop() // 只为释放麦克风；结果直接丢弃
            finishOnMain()
        }
    }

    /**
     * 收尾：把 stop() 拿到的字节直接交给 STT。
     *
     * 注意 AudioRecorder.stop() 返回的**已经是完整 WAV（44 字节头 + PCM）**，
     * 它内部用 WavHeader.buildWavHeader(16000, 1, 16) 补过头；这里**不能再用
     * WavHeader.buildWavHeader 补一次**，否则双头会让 ASR 解出错误采样长度。
     * 空数组就是「没录到声音」，不要伪装成只有头的空 WAV。
     */
    private suspend fun handleWav(wav: ByteArray) {
        if (cancelled) {
            finishOnMain()
            return
        }
        if (wav.isEmpty()) {
            finishWithToast("没录到声音，请重试")
            return
        }

        val result = (applicationContext as CompanionApp).sttManager.transcribe(wav)
        if (cancelled) {
            finishOnMain()
            return
        }

        when (result) {
            is AsrResult.Success -> {
                val text = normalizeVoiceText(result.text)
                if (text == null) {
                    // Success 但 trim 后为空：不拉主界面、不写 pref
                    finishWithToast("没听清，请再说一次")
                    return
                }
                // put 是 suspend DAO，必须在后台线程（本协程已在 Dispatchers.IO）
                (applicationContext as CompanionApp).database.appStateDao()
                    .put(AppStateEntity(AppStateEntity.KEY_VOICE_PENDING_TEXT, text))
                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@VoiceInputActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    finish()
                }
            }

            is AsrResult.Failure -> finishWithToast(result.reason)
        }
    }

    override fun onPause() {
        // 识别途中（录音已停、正在 stop / 转写）**不取消**：话已经说完了，用户切走往往只是
        // 去看一眼别的消息，整段结果仍应照常回填。
        // 此前无条件 `cancelled = true`：此时 `recording` 已被置 false → 落到 else 分支直接
        // finish()，连 toast 都没有 → 表现为「说完点结束、手一滑切走，语音整段消失」（V2.3.6 ⚠️）。
        //
        // 判据只看 volatile 的 recording / busy，与 super.onPause() 先后无关，故先判后调、尽早退出。
        val transcribing = !recording.value && busy
        super.onPause()
        if (transcribing) return

        // 录音中被盖住：继续录毫无意义，丢弃这次会话（不上传、不写 pref、不拉主界面）。
        cancelled = true
        if (recording.value && stopGuard.compareAndSet(false, true)) {
            // 录音中：先 stop 释放麦克风，再 finish
            ioScope.launch {
                recorder.stop()
                finishOnMain()
            }
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        // 销毁后不再有界面承接结果；取消 IO 协程避免 stop/transcribe 泄漏。
        cancelled = true
        ioScope.cancel()
        super.onDestroy()
    }

    private suspend fun finishWithToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@VoiceInputActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun finishOnMain() = withContext(Dispatchers.Main) { finish() }
}
