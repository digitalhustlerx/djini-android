package com.wangzan101.djini.core.buddy

import com.wangzan101.djini.core.capability.Capability
import com.wangzan101.djini.core.capability.CapabilityKey
import com.wangzan101.djini.core.capability.CapabilityStatus
import com.wangzan101.djini.core.capability.PromptEnv
import com.wangzan101.djini.core.tool.AgentTool
import com.wangzan101.djini.core.tool.Args
import com.wangzan101.djini.core.tool.Risk
import com.wangzan101.djini.core.tool.ToolContext
import com.wangzan101.djini.core.tool.ToolDef
import com.wangzan101.djini.core.tool.ToolExecution
import com.wangzan101.djini.core.tool.Verb
import com.wangzan101.djini.core.tool.bool
import com.wangzan101.djini.core.tool.int
import com.wangzan101.djini.core.tool.ok
import com.wangzan101.djini.core.tool.str
import com.wangzan101.djini.core.tool.strArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val BUDDY = "buddy"

/**
 * Host bridge for Djini Pulse: digital habit analytics, programmable dynamic haptics,
 * Focus Guardian sessions, and recursive long-term habit insights.
 */
interface BuddyPort {
    suspend fun habitStats(days: Int): JsonObject
    suspend fun playHaptic(patternId: String?, timingsMs: List<Long>?, amplitudes: List<Int>?): JsonObject
    suspend fun saveHaptic(pattern: HapticPattern): JsonObject
    suspend fun focusControl(
        action: String,
        goal: String?,
        durationMinutes: Int?,
        distractionPackages: Set<String>?,
        autoBounce: Boolean?,
        doomscrollThreshold: Int?,
        vipKeywords: List<String>?,
        personalityTone: String?,
    ): JsonObject
    suspend fun saveInsight(category: String, summary: String, recommendation: String, sourceDays: Int): JsonObject
}

internal class BuddyCapability(val port: BuddyPort) : Capability {
    override val id = BUDDY
    override fun status(): CapabilityStatus = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = buddyTools
    override fun sections(env: PromptEnv): List<String> = listOf(
        "## Djini Pulse (Active Companion, Habit Analytics & Dynamic Haptics)\n" +
            "- You have live on-device digital habit telemetry (`buddy_habit_stats`), programmable haptic feedback (`buddy_haptic_play`, `buddy_haptic_save`), Focus Guardian control (`buddy_focus_control`), and recursive habit insight memory (`buddy_insight_save`).\n" +
            "- When the user asks about screen time, phone habits, doomscrolling, focus accountability, or daily reflections, read `buddy_habit_stats` first to ground your advice in real on-device metrics and prior insights.\n" +
            "- Use `buddy_haptic_play` intentionally to make Djini feel alive (e.g. `gentle_tap`, `heartbeat`, `doomscroll_nudge`, `urgent_alert`, `focus_win`, `breathing_calm`, or custom waveforms). Program reusable signatures with `buddy_haptic_save` when the user wants a custom feel.\n" +
            "- After synthesizing meaningful recurring patterns or coaching agreements, persist them via `buddy_insight_save` so future sessions build recursively on what works for this user.",
    )
    override fun onTaskStart(ctx: ToolContext) { ctx[KEY] = this }
    companion object { val KEY = CapabilityKey<BuddyCapability>(BUDDY) }
}

private val buddyTools: List<AgentTool> = listOf(
    BuddyHabitStatsTool(),
    BuddyHapticPlayTool(),
    BuddyHapticSaveTool(),
    BuddyFocusControlTool(),
    BuddyInsightSaveTool(),
)

private fun intArraySchema(desc: String) = buildJsonObject {
    put("type", "array")
    put("description", desc)
    put("items", buildJsonObject { put("type", "integer") })
}

private fun okResult(data: JsonObject): ToolExecution = ok {
    data.forEach { (k, v) -> put(k, v) }
}

private class BuddyHabitStatsTool : AgentTool {
    override val capability = BUDDY
    /** Read-only telemetry tool; offScreen=false so readOnly==true and it runs via executeRead without write gates. */
    override val offScreen = false
    override val verb = Verb.SelfStatus
    override val risk = Risk.Read
    override val concurrency = com.wangzan101.djini.core.tool.Concurrency.ParallelSafe
    override val label = "Read habit analytics"
    override val def = ToolDef(
        name = "buddy_habit_stats",
        description = "Read on-device digital habit metrics: today's screen time, pickups, top apps, scroll bursts, doomscroll episodes, active focus session, multi-day trends, available haptic patterns, and stored recursive habit insights.",
        props = mapOf(
            "days" to int("Number of days to synthesize (1..30, default 7).", min = 1, max = 30),
        ),
    )
    override fun available(ctx: ToolContext) = ctx[BuddyCapability.KEY] != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val days = a.int("days", 1, 30) ?: 7
        return okResult(ctx[BuddyCapability.KEY]!!.port.habitStats(days))
    }
}

private class BuddyHapticPlayTool : AgentTool {
    override val capability = BUDDY
    override val offScreen = true
    override val verb = Verb.SelfNotify
    override val risk = Risk.Write
    override val label = "Play haptic feedback"
    override val def = ToolDef(
        name = "buddy_haptic_play",
        description = "Trigger a dynamic physical haptic pattern on the phone. Pass either a saved patternId (e.g. gentle_tap, heartbeat, doomscroll_nudge, urgent_alert, focus_win, breathing_calm) OR custom timingsMs + amplitudes arrays.",
        props = mapOf(
            "patternId" to str("Built-in or custom saved haptic pattern id."),
            "timingsMs" to intArraySchema("Optional custom waveform durations in ms (alternating off/on, 1..16 entries, max 6000ms total)."),
            "amplitudes" to intArraySchema("Optional custom amplitudes (0..255) matching timingsMs length."),
        ),
    )
    override fun available(ctx: ToolContext) = ctx[BuddyCapability.KEY] != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val patternId = a.str("patternId", max = 64)
        val timings = (a.o["timingsMs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        val amplitudes = (a.o["amplitudes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        return okResult(ctx[BuddyCapability.KEY]!!.port.playHaptic(patternId, timings, amplitudes))
    }
}

private class BuddyHapticSaveTool : AgentTool {
    override val capability = BUDDY
    override val offScreen = true
    override val verb = Verb.SettingsSet
    override val risk = Risk.Write
    override val label = "Save haptic pattern"
    override val def = ToolDef(
        name = "buddy_haptic_save",
        description = "Define and save a named reusable haptic behavior so Djini or the user can replay it for routines, reminders, or emotional cues.",
        props = mapOf(
            "id" to str("Short identifier (alphanumeric, _ or -), e.g. morning_pulse."),
            "name" to str("Human-readable label."),
            "description" to str("When or why this haptic signature is used."),
            "timingsMs" to intArraySchema("Waveform durations in ms (1..16 entries, max 6000ms total)."),
            "amplitudes" to intArraySchema("Amplitudes 0..255 matching timingsMs."),
        ),
        required = listOf("id", "name", "timingsMs", "amplitudes"),
    )
    override fun available(ctx: ToolContext) = ctx[BuddyCapability.KEY] != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val id = a.str("id", min = 1, max = 48)!!
        val name = a.str("name", min = 1, max = 60)!!
        val description = a.str("description", max = 200).orEmpty()
        val timings = (a.o["timingsMs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
        val amplitudes = (a.o["amplitudes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        val pattern = HapticPattern(id, name, description, timings, amplitudes, builtIn = false)
        return okResult(ctx[BuddyCapability.KEY]!!.port.saveHaptic(pattern))
    }
}

private class BuddyFocusControlTool : AgentTool {
    override val capability = BUDDY
    override val offScreen = true
    override val verb = Verb.SettingsSet
    override val risk = Risk.Write
    override val label = "Manage focus guardian"
    override val def = ToolDef(
        name = "buddy_focus_control",
        description = "Start, stop, or configure Djini's active Focus Guardian & doomscroll rules (goal, duration, distraction apps, auto-bounce, scroll sensitivity, VIP alert keywords, coaching tone).",
        props = mapOf(
            "action" to str("One of: start, stop, status, configure.", enum = listOf("start", "stop", "status", "configure")),
            "goal" to str("What the user is focusing on (for action=start)."),
            "durationMinutes" to int("Focus duration in minutes (5..480, default 45).", min = 5, max = 480),
            "distractionPackages" to strArray("Optional Android package names to guard against.", max = 40),
            "autoBounce" to bool("If true, gently bounce user to Home when they open a distraction app during focus."),
            "doomscrollThreshold" to int("Scroll events within 60s before nudging (5..120).", min = 5, max = 120),
            "vipKeywords" to strArray("Keywords in incoming emails/notifications that trigger VIP urgent haptic alerts.", max = 40),
            "personalityTone" to str("Coaching style: direct, warm, or coach.", enum = listOf("direct", "warm", "coach")),
        ),
        required = listOf("action"),
    )
    override fun available(ctx: ToolContext) = ctx[BuddyCapability.KEY] != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val action = a.str("action", min = 1, max = 24)!!
        val goal = a.str("goal", max = 200)
        val duration = a.int("durationMinutes", 5, 480)
        val pkgs = (a.o["distractionPackages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
        val autoBounce = (a.o["autoBounce"] as? JsonPrimitive)?.booleanOrNull
        val threshold = a.int("doomscrollThreshold", 5, 120)
        val keywords = (a.o["vipKeywords"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        val tone = a.str("personalityTone", max = 24)
        return okResult(ctx[BuddyCapability.KEY]!!.port.focusControl(action, goal, duration, pkgs, autoBounce, threshold, keywords, tone))
    }
}

private class BuddyInsightSaveTool : AgentTool {
    override val capability = BUDDY
    override val offScreen = true
    override val verb = Verb.MemorySave
    override val risk = Risk.Write
    override val label = "Save habit insight"
    override val def = ToolDef(
        name = "buddy_insight_save",
        description = "Persist a synthesized digital-habit or quality-of-life insight so Djini learns recursively across days and improves future coaching and routines.",
        props = mapOf(
            "category" to str("One of: habit, trigger, focus, routine, wellbeing."),
            "summary" to str("Concise synthesized pattern observed from the user's phone usage or behavior."),
            "recommendation" to str("Actionable adjustment or rule Djini should apply or suggest next."),
            "sourceDays" to int("How many days of data informed this insight (default 7).", min = 1, max = 60),
        ),
        required = listOf("category", "summary", "recommendation"),
    )
    override fun available(ctx: ToolContext) = ctx[BuddyCapability.KEY] != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val category = a.str("category", min = 1, max = 32)!!
        val summary = a.str("summary", min = 1, max = 400)!!
        val recommendation = a.str("recommendation", min = 1, max = 400)!!
        val sourceDays = a.int("sourceDays", 1, 60) ?: 7
        return okResult(ctx[BuddyCapability.KEY]!!.port.saveInsight(category, summary, recommendation, sourceDays))
    }
}

fun HabitSynthesis.toJson(patterns: List<HapticPattern>): JsonObject = buildJsonObject {
    put("daysAnalyzed", daysAnalyzed)
    put("todayDateIso", todayDateIso)
    put("todayForegroundMinutes", todayForegroundMinutes)
    put("todayPickups", todayPickups)
    put("todayScrollEvents", todayScrollEvents)
    put("todayDoomscrollEpisodes", todayDoomscrollEpisodes)
    put("todayFocusMinutes", todayFocusMinutes)
    put("todayNudgesSent", todayNudgesSent)
    put("watchedAppsSharePercent", watchedSharePercent)
    put("topAppsToday", buildJsonArray {
        topAppsToday.forEach { app ->
            add(buildJsonObject {
                put("packageName", app.packageName)
                put("appLabel", app.appLabel.ifBlank { app.packageName })
                put("foregroundMinutes", (app.foregroundMs / 60_000L).toInt())
                put("scrollEvents", app.scrollEvents)
                put("openCount", app.openCount)
                put("doomscrollEpisodes", app.doomscrollEpisodes)
            })
        }
    })
    put("dailySeries", buildJsonArray {
        dailySeries.forEach { d ->
            add(buildJsonObject {
                put("date", d.dateIso)
                put("foregroundMinutes", d.foregroundMinutes)
                put("pickups", d.pickups)
                put("scrollEvents", d.scrollEvents)
                put("focusMinutes", d.focusMinutes)
            })
        }
    })
    activeFocus?.let { f ->
        put("activeFocus", buildJsonObject {
            put("id", f.id)
            put("goal", f.goal)
            put("autoBounce", f.autoBounce)
            put("nudgesSent", f.nudgesSent)
            put("endsAtMs", f.endsAtMs)
        })
    }
    put("availableHaptics", buildJsonArray {
        patterns.forEach { p ->
            add(buildJsonObject {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put("builtIn", p.builtIn)
            })
        }
    })
    put("recentInsights", buildJsonArray {
        recentInsights.forEach { i ->
            add(buildJsonObject {
                put("id", i.id)
                put("category", i.category)
                put("summary", i.summary)
                put("recommendation", i.recommendation)
                put("sourceDays", i.sourceDays)
            })
        }
    })
}
