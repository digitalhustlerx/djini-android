package com.wangzan101.djini.core.buddy

import kotlinx.serialization.Serializable

/**
 * Programmable haptic signature: alternating wait/vibrate durations ([timingsMs]) paired with
 * per-step amplitudes (0..255). Bounded for safety so neither users nor models can create
 * runaway or uncomfortable vibrations.
 */
@Serializable
data class HapticPattern(
    val id: String,
    val name: String,
    val description: String,
    val timingsMs: List<Long>,
    val amplitudes: List<Int>,
    val builtIn: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id.length <= 48 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "Haptic pattern id must be 1..48 alphanumeric/_/- characters"
        }
        require(name.isNotBlank() && name.length <= 60) { "Haptic pattern name must be 1..60 chars" }
        require(timingsMs.isNotEmpty() && timingsMs.size <= MAX_STEPS) {
            "timingsMs must have 1..$MAX_STEPS entries"
        }
        require(timingsMs.size == amplitudes.size) { "timingsMs and amplitudes must have the same length" }
        require(timingsMs.all { it in 0L..MAX_STEP_MS }) { "Each timing step must be in 0..${MAX_STEP_MS}ms" }
        require(timingsMs.sum() in 1L..MAX_TOTAL_MS) { "Total haptic duration must be 1..${MAX_TOTAL_MS}ms" }
        require(amplitudes.all { it in 0..255 }) { "Each amplitude must be in 0..255" }
    }

    companion object {
        const val MAX_STEPS = 16
        const val MAX_STEP_MS = 1_500L
        const val MAX_TOTAL_MS = 6_000L

        val BUILT_IN: List<HapticPattern> = listOf(
            HapticPattern(
                id = "gentle_tap",
                name = "Gentle Tap",
                description = "Subtle single tap to let you know Djini is paying attention",
                timingsMs = listOf(0L, 38L),
                amplitudes = listOf(0, 115),
                builtIn = true,
            ),
            HapticPattern(
                id = "heartbeat",
                name = "Heartbeat",
                description = "Warm double-pulse companion check-in",
                timingsMs = listOf(0L, 55L, 95L, 85L),
                amplitudes = listOf(0, 145, 0, 215),
                builtIn = true,
            ),
            HapticPattern(
                id = "doomscroll_nudge",
                name = "Doomscroll Nudge",
                description = "Escalating three-beat pulse to break mindless scrolling",
                timingsMs = listOf(0L, 90L, 75L, 125L, 75L, 185L),
                amplitudes = listOf(0, 165, 0, 215, 0, 255),
                builtIn = true,
            ),
            HapticPattern(
                id = "urgent_alert",
                name = "VIP / Urgent Alert",
                description = "Crisp triple-tap for high-priority emails or messages",
                timingsMs = listOf(0L, 60L, 55L, 60L, 55L, 140L),
                amplitudes = listOf(0, 225, 0, 225, 0, 255),
                builtIn = true,
            ),
            HapticPattern(
                id = "focus_win",
                name = "Focus Milestone",
                description = "Rising celebratory triple-tick when you complete a focus block",
                timingsMs = listOf(0L, 28L, 48L, 38L, 48L, 70L),
                amplitudes = listOf(0, 95, 0, 160, 0, 235),
                builtIn = true,
            ),
            HapticPattern(
                id = "breathing_calm",
                name = "Breathing Calm",
                description = "Slow grounding two-wave pulse to reset attention",
                timingsMs = listOf(0L, 180L, 160L, 260L),
                amplitudes = listOf(0, 85, 0, 130),
                builtIn = true,
            ),
        )
    }
}

@Serializable
data class AppDailyStats(
    val packageName: String,
    val appLabel: String = "",
    val foregroundMs: Long = 0L,
    val scrollEvents: Int = 0,
    val openCount: Int = 0,
    val doomscrollEpisodes: Int = 0,
    val lastSeenMs: Long = 0L,
)

@Serializable
data class DailyHabitRecord(
    val dateIso: String,
    val pickups: Int = 0,
    val totalForegroundMs: Long = 0L,
    val totalScrollEvents: Int = 0,
    val focusCompletedMinutes: Int = 0,
    val nudgesSent: Int = 0,
    val apps: Map<String, AppDailyStats> = emptyMap(),
)

@Serializable
data class FocusSession(
    val id: String,
    val goal: String,
    val startedAtMs: Long,
    val endsAtMs: Long,
    val distractionPackages: Set<String> = emptySet(),
    val autoBounce: Boolean = false,
    val nudgesSent: Int = 0,
    val conversationId: String? = null,
) {
    fun isActive(nowMs: Long): Boolean = nowMs < endsAtMs
    fun remainingMinutes(nowMs: Long): Int = ((endsAtMs - nowMs).coerceAtLeast(0L) / 60_000L).toInt()
}

@Serializable
data class BuddyInsight(
    val id: String,
    val createdAtMs: Long,
    val category: String,
    val summary: String,
    val recommendation: String,
    val sourceDays: Int = 1,
)

@Serializable
data class BuddyConfig(
    val enabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val doomscrollScrollThreshold: Int = 28,
    val doomscrollWindowSec: Int = 60,
    val nudgeCooldownSec: Int = 180,
    val autoBounceOnFocusDrift: Boolean = false,
    val watchPackages: Set<String> = DEFAULT_WATCH_PACKAGES,
    val vipKeywords: List<String> = DEFAULT_VIP_KEYWORDS,
    val personalityTone: String = "direct",
    val customHaptics: List<HapticPattern> = emptyList(),
) {
    fun allHaptics(): List<HapticPattern> {
        val byId = LinkedHashMap<String, HapticPattern>()
        HapticPattern.BUILT_IN.forEach { byId[it.id] = it }
        customHaptics.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    fun hapticById(id: String): HapticPattern? =
        customHaptics.firstOrNull { it.id == id } ?: HapticPattern.BUILT_IN.firstOrNull { it.id == id }

    companion object {
        val DEFAULT_WATCH_PACKAGES: Set<String> = setOf(
            "com.zhiliaoapp.musically",      // TikTok
            "com.ss.android.ugc.trill",      // TikTok alt
            "com.instagram.android",         // Instagram
            "com.twitter.android",           // X / Twitter
            "com.google.android.youtube",    // YouTube
            "com.reddit.frontpage",          // Reddit
            "com.facebook.katana",           // Facebook
            "com.snapchat.android",          // Snapchat
            "com.instagram.barcelona",       // Threads
        )

        val DEFAULT_VIP_KEYWORDS: List<String> = listOf(
            "urgent", "asap", "deadline", "invoice", "interview", "flight", "security alert", "payment due",
        )
    }
}

@Serializable
data class BuddyLedgerState(
    val days: Map<String, DailyHabitRecord> = emptyMap(),
    val activeFocus: FocusSession? = null,
    val insights: List<BuddyInsight> = emptyList(),
)

sealed interface BuddyIntervention {
    val packageName: String
    val appLabel: String
    val hapticPatternId: String
    val title: String
    val message: String
    val suggestedPrompt: String
    val autoBounce: Boolean

    data class Doomscroll(
        override val packageName: String,
        override val appLabel: String,
        val scrollsInWindow: Int,
        val minutesToday: Int,
        override val hapticPatternId: String = "doomscroll_nudge",
        override val title: String,
        override val message: String,
        override val suggestedPrompt: String,
        override val autoBounce: Boolean = false,
    ) : BuddyIntervention

    data class FocusDrift(
        override val packageName: String,
        override val appLabel: String,
        val focusGoal: String,
        val remainingMinutes: Int,
        override val hapticPatternId: String = "doomscroll_nudge",
        override val title: String,
        override val message: String,
        override val suggestedPrompt: String,
        override val autoBounce: Boolean,
    ) : BuddyIntervention
}

data class HabitDayPoint(
    val dateIso: String,
    val foregroundMinutes: Int,
    val pickups: Int,
    val scrollEvents: Int,
    val focusMinutes: Int,
)

data class HabitSynthesis(
    val daysAnalyzed: Int,
    val todayDateIso: String,
    val todayForegroundMinutes: Int,
    val todayPickups: Int,
    val todayScrollEvents: Int,
    val todayDoomscrollEpisodes: Int,
    val todayFocusMinutes: Int,
    val todayNudgesSent: Int,
    val watchedSharePercent: Int,
    val topAppsToday: List<AppDailyStats>,
    val dailySeries: List<HabitDayPoint>,
    val activeFocus: FocusSession?,
    val recentInsights: List<BuddyInsight>,
)
