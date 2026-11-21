package com.companion.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 版本号一致性守门（1.9.4.0 补）。
 *
 * 项目规则里记着一次事故：两条线同时发版，各自改 build.gradle 的 versionCode，
 * 互相覆盖 → 两个 CHANGELOG 条目指向同一个 code。这里用单测把"唯一事实源"钉死：
 *  - `app/build.gradle` 的 versionCode / versionName == CHANGELOG 最新条目；
 *  - CHANGELOG 所有版本条目的 versionCode **唯一且严格递增**（越新的版本 code 越大）。
 *
 * Gradle 单测的 user.dir 可能是项目根也可能是模块根，故用多候选路径定位，找不到就跳过（避免 CI 环境差异导致假失败）。
 */
class VersionConsistencyTest {

    private fun find(vararg candidates: String): File? =
        candidates.map { File(it) }.firstOrNull { it.isFile }

    private fun buildGradle(): File? = find("app/build.gradle", "build.gradle")

    private fun changelog(): File? = find("../CHANGELOG.md", "CHANGELOG.md", "../../CHANGELOG.md")

    @Test
    fun build_gradle_matches_latest_changelog_entry() {
        val gradle = buildGradle() ?: return
        val log = changelog() ?: return
        val g = gradle.readText()
        val code = Regex("""versionCode\s+(\d+)""").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: return
        val name = Regex("""versionName\s+"([^"]+)"""").find(g)?.groupValues?.get(1) ?: return

        val entry = Regex("""(?m)^## \[(\d+\.\d+\.\d+(?:\.\d+)?)\][^\n]*·\s*versionCode\s+(\d+)""")
            .find(log.readText()) ?: return
        assertEquals("versionName 与 CHANGELOG 最新条目不一致", entry.groupValues[1], name)
        assertEquals("versionCode 与 CHANGELOG 最新条目不一致", entry.groupValues[2].toInt(), code)
    }

    @Test
    fun changelog_version_codes_are_unique_and_increasing() {
        val log = changelog() ?: return
        val codes = Regex("""(?m)^## \[[\d.]+\][^\n]*·\s*versionCode\s+(\d+)""")
            .findAll(log.readText()).map { it.groupValues[1].toInt() }.toList()
        if (codes.size < 2) return
        assertEquals("versionCode 出现重复（并发改版撞号）", codes.size, codes.toSet().size)
        for (i in 1 until codes.size) {
            assertTrue(
                "CHANGELOG 应按 versionCode 从新到旧排列，但 ${codes[i - 1]} 之后出现 ${codes[i]}",
                codes[i - 1] > codes[i]
            )
        }
    }
}
