package com.companion.chat.data.power

import com.companion.chat.domain.power.RomFamily
import com.companion.chat.domain.settings.DndSettings

/**
 * WakeLock 超时与 tag 统一策略（任务 6.1）。纯常量，可 JVM 单测。
 *
 * - [TIMEOUT_MS]：**5 分钟强制释放**（计划 6.1 验收）
 * - [RECEIVER_TIMEOUT_MS]：Receiver 只做亚秒级 DB 入队，10s 就够，更早兜底
 * - [BOOT_TIMEOUT_MS]：开机恢复档——要冷启动打开 Room、读全表、连排 N 个闹钟，
 *   比「入队」重得多，10s 档会在行数多/开机 IO 抢占时被回收；30s 仍在后台广播 60s 窗口内
 * - [TTS_TIMEOUT_MS]：TTS 朗读是唯一例外（长段落需要更久，4.2 审查从 5→10 分钟）
 *
 * 各 tag 会出现在 `adb shell dumpsys power` 的 wake lock 列表里，真机按 tag 核对是否残留。
 */
object WakeLockPolicy {
    const val TIMEOUT_MS = 5 * 60_000L
    const val RECEIVER_TIMEOUT_MS = 10_000L
    const val BOOT_TIMEOUT_MS = 30_000L
    const val TTS_TIMEOUT_MS = 10 * 60_000L

    const val TAG_CHECK = "companion:proactive_check"
    const val TAG_DELIVERY = "companion:proactive_delivery"
    const val TAG_REMINDER = "companion:reminder_alarm"
    const val TAG_TTS = "companion:tts_read"

    /** 每日任务到点生成/送达（2026-11-23）：网络生成 + 入库 + 通知 + 可选语音，用 TIMEOUT_MS 档。 */
    const val TAG_DAILY = "companion:daily_task"

    /** 开机/升级后恢复闹钟（B9）：重启恢复要读 Room + 多次排闹钟，用 Receiver 的 10s 档。 */
    const val TAG_BOOT = "companion:boot_restore"

    val ALL_TAGS = listOf(TAG_CHECK, TAG_DELIVERY, TAG_REMINDER, TAG_TTS, TAG_DAILY, TAG_BOOT)
}

/** 策略判定结果（日志 / 设置页诊断用）。 */
enum class PolicyState {
    UNKNOWN,
    SCREEN_ON,        // 亮屏 → 没必要持锁
    INCOMING_CALL,    // 来电 → 让系统优先响铃
    DND_BLOCK,        // 系统勿扰 + 用户不允许陪聊在勿扰里出声
    KEEP_WAKELOCK     // 息屏 + 无来电 + 可播报 → 保持
}

/**
 * 持有期间的「该不该继续持锁」判据（任务 6.1）。
 *
 * **纯逻辑**：亮灭屏 / 来电 / 系统勿扰都由调用方传入（生产见 [SystemDndState]），
 * 因此不需要 Context，可 JVM 单测。真正放锁动作由编排层（[WakeLockScope]）执行。
 *
 * 判据顺序：
 *  1. 亮屏 → 释放（用户在用，系统自会调度）
 *  2. 来电 → 释放（电话优先）
 *  3. 系统勿扰 且 用户不允许陪聊在勿扰里出声 → 释放
 *  4. 其余（息屏 + 无来电 + 可播报）→ 保持
 */
object WakeLockPolicyEvaluator {

    fun evaluate(
        screenOn: Boolean,
        incomingCall: Boolean,
        systemDndActive: Boolean,
        dndSettings: DndSettings = DndSettings.DISABLED,
        log: (String) -> Unit = {}
    ): WakeLockDecision {
        if (screenOn) {
            log("屏幕亮 → 释放 WakeLock（用户在用）")
            return WakeLockDecision(false, PolicyState.SCREEN_ON)
        }
        if (incomingCall) {
            log("屏幕灭 + 来电 → 释放 WakeLock（电话优先）")
            return WakeLockDecision(false, PolicyState.INCOMING_CALL)
        }
        if (systemDndActive && !dndSettings.allowChatInDnd) {
            log("屏幕灭 + 系统勿扰 + 不允许陪聊出声 → 释放 WakeLock")
            return WakeLockDecision(false, PolicyState.DND_BLOCK)
        }
        log("屏幕灭 + 无来电 + 可播报 → 保持 WakeLock")
        return WakeLockDecision(true, PolicyState.KEEP_WAKELOCK)
    }
}

/** 一次判据结果：是否继续持锁 + 原因状态。 */
data class WakeLockDecision(
    val shouldKeep: Boolean,
    val state: PolicyState
)

/**
 * 诊断快照（设置页/日志展示用）：把当前所有判据摊平，便于真机排查「为什么没播」。
 */
data class DiagnosticSnapshot(
    val rom: RomFamily,
    val screenOn: Boolean,
    val incomingCall: Boolean,
    val systemDndActive: Boolean,
    val allowChatInDnd: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val shouldKeepWakeLock: Boolean,
    val policyState: PolicyState
)