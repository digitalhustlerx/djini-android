package com.wangzan101.djini.core.buddy

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.UUID

/**
 * On-device persistent digital habit ledger, doomscroll detector, focus watchdog, and recursive
 * insight store. Designed to run 24/7 with zero network overhead and bounded 60-day storage.
 */
class HabitLedger(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val configFile = File(dir, "config-v1.json")
    private val stateFile = File(dir, "ledger-v1.json")

    private var config: BuddyConfig = loadConfig()
    private var state: BuddyLedgerState = loadState()

    // In-memory rolling sensor state (not persisted per-event to protect flash & battery)
    private var currentPkg: String? = null
    private var currentLabel: String = ""
    private var currentEnteredAtMs: Long = 0L
    private val scrollWindow = ArrayDeque<Pair<Long, String>>()
    private val lastNudgeByPkg = HashMap<String, Long>()
    private var dirty: Boolean = false

    @Synchronized fun config(): BuddyConfig = config

    @Synchronized fun state(): BuddyLedgerState = state

    @Synchronized fun updateConfig(transform: (BuddyConfig) -> BuddyConfig): BuddyConfig {
        config = transform(config)
        saveAtomic(configFile, json.encodeToString(config))
        return config
    }

    @Synchronized fun saveHapticPattern(pattern: HapticPattern): HapticPattern {
        val next = config.customHaptics.filterNot { it.id == pattern.id } + pattern.copy(builtIn = false)
        updateConfig { it.copy(customHaptics = next.takeLast(MAX_CUSTOM_HAPTICS)) }
        return pattern
    }

    @Synchronized fun recordPickup(nowMs: Long = clock()) {
        val day = dateKey(nowMs)
        val rec = state.days[day] ?: DailyHabitRecord(day)
        updateDay(rec.copy(pickups = rec.pickups + 1))
    }

    /**
     * Record a foreground app transition from Accessibility or UsageStats. Returns a [BuddyIntervention]
     * if the user drifted into a distraction app during an active [FocusSession].
     */
    @Synchronized fun onForegroundApp(
        packageName: String,
        appLabel: String = packageName,
        nowMs: Long = clock(),
    ): BuddyIntervention? {
        if (packageName.isBlank()) return null
        val prev = currentPkg
        if (prev != null && prev != packageName && currentEnteredAtMs > 0L) {
            val delta = (nowMs - currentEnteredAtMs).coerceIn(0L, MAX_SINGLE_SESSION_MS)
            if (delta >= 1_000L) addForegroundDuration(prev, currentLabel, delta, nowMs)
        }
        val openedNew = prev != packageName
        currentPkg = packageName
        currentLabel = appLabel.ifBlank { packageName }
        currentEnteredAtMs = nowMs

        if (openedNew) {
            val day = dateKey(nowMs)
            val rec = state.days[day] ?: DailyHabitRecord(day)
            val prevApp = rec.apps[packageName] ?: AppDailyStats(packageName, currentLabel)
            val nextApp = prevApp.copy(
                appLabel = currentLabel,
                openCount = prevApp.openCount + 1,
                lastSeenMs = nowMs,
            )
            updateDay(rec.copy(apps = rec.apps + (packageName to nextApp)))
        }

        return checkFocusDrift(packageName, currentLabel, nowMs)
    }

    /**
     * Merge authoritative system UsageStats foreground durations for today (e.g. from Android
     * UsageStatsManager) without double-counting: takes max(observed, systemMs) per app.
     */
    @Synchronized fun mergeSystemUsage(
        dateIso: String,
        perAppMs: Map<String, Pair<String, Long>>,
        nowMs: Long = clock(),
    ) {
        if (perAppMs.isEmpty()) return
        val rec = state.days[dateIso] ?: DailyHabitRecord(dateIso)
        val mergedApps = rec.apps.toMutableMap()
        for ((pkg, pair) in perAppMs) {
            val (label, ms) = pair
            if (ms <= 0L) continue
            val existing = mergedApps[pkg] ?: AppDailyStats(pkg, label.ifBlank { pkg })
            mergedApps[pkg] = existing.copy(
                appLabel = label.ifBlank { existing.appLabel.ifBlank { pkg } },
                foregroundMs = maxOf(existing.foregroundMs, ms),
                lastSeenMs = maxOf(existing.lastSeenMs, nowMs),
            )
        }
        val totalMs = mergedApps.values.sumOf { it.foregroundMs }
        updateDay(rec.copy(totalForegroundMs = maxOf(rec.totalForegroundMs, totalMs), apps = mergedApps))
    }

    /**
     * Record a UI scroll event on [packageName]. Detects rapid doomscrolling bursts on watched apps
     * and returns a [BuddyIntervention.Doomscroll] when threshold + cooldown conditions are met.
     */
    @Synchronized fun onScroll(
        packageName: String,
        appLabel: String = packageName,
        nowMs: Long = clock(),
    ): BuddyIntervention? {
        if (packageName.isBlank()) return null
        val label = appLabel.ifBlank { packageName }
        val day = dateKey(nowMs)
        val rec = state.days[day] ?: DailyHabitRecord(day)
        val prevApp = rec.apps[packageName] ?: AppDailyStats(packageName, label)
        val nextApp = prevApp.copy(
            appLabel = label,
            scrollEvents = prevApp.scrollEvents + 1,
            lastSeenMs = nowMs,
        )
        updateDay(rec.copy(totalScrollEvents = rec.totalScrollEvents + 1, apps = rec.apps + (packageName to nextApp)))

        if (!config.enabled) return null
        val focus = activeFocusLocked(nowMs)
        val watched = packageName in config.watchPackages || (focus != null && packageName in focus.distractionPackages)
        if (!watched) return null

        val windowMs = config.doomscrollWindowSec.coerceIn(15, 600) * 1_000L
        scrollWindow.addLast(nowMs to packageName)
        while (scrollWindow.isNotEmpty() && nowMs - scrollWindow.first().first > windowMs) {
            scrollWindow.removeFirst()
        }
        val countInWindow = scrollWindow.count { it.second == packageName }
        if (countInWindow < config.doomscrollScrollThreshold.coerceAtLeast(5)) return null
        if (!cooldownElapsed(packageName, nowMs)) return null

        lastNudgeByPkg[packageName] = nowMs
        // Clear this package's window after firing so the next episode needs a fresh burst
        val remaining = scrollWindow.filter { it.second != packageName }
        scrollWindow.clear()
        scrollWindow.addAll(remaining)

        val updatedApp = (state.days[day]?.apps?.get(packageName) ?: nextApp).let {
            it.copy(doomscrollEpisodes = it.doomscrollEpisodes + 1)
        }
        val latestRec = state.days[day] ?: rec
        updateDay(latestRec.copy(nudgesSent = latestRec.nudgesSent + 1, apps = latestRec.apps + (packageName to updatedApp)))
        flush()

        val minsToday = (updatedApp.foregroundMs / 60_000L).toInt()
        val toneLine = when (config.personalityTone) {
            "warm" -> "Gentle check-in: you've scrolled $countInWindow times in ${config.doomscrollWindowSec}s on $label. Want to pause and reset?"
            "coach" -> "Heads up! $countInWindow rapid scrolls on $label (${minsToday}m today). Let's snap back to what matters."
            else -> "Caught a scroll loop on $label ($countInWindow scrolls in ${config.doomscrollWindowSec}s, ${minsToday}m today). Tap to check in."
        }
        val prompt = buildString {
            append("You just caught me doomscrolling on $label ($countInWindow scrolls in ${config.doomscrollWindowSec}s, ${minsToday}m today). ")
            if (focus != null) append("My active focus goal is: \"${focus.goal}\". ")
            append("Call me out directly, help me snap out of it, and suggest one concrete 2-minute next step.")
        }
        return BuddyIntervention.Doomscroll(
            packageName = packageName,
            appLabel = label,
            scrollsInWindow = countInWindow,
            minutesToday = minsToday,
            title = "Djini Pulse · Scroll check on $label",
            message = toneLine,
            suggestedPrompt = prompt,
            autoBounce = focus?.autoBounce == true,
        )
    }

    @Synchronized fun startFocusSession(
        goal: String,
        durationMinutes: Int,
        distractionPackages: Set<String> = config.watchPackages,
        autoBounce: Boolean = config.autoBounceOnFocusDrift,
        conversationId: String? = null,
        nowMs: Long = clock(),
    ): FocusSession {
        val cleanGoal = goal.trim().ifBlank { "Deep Focus" }.take(200)
        val mins = durationMinutes.coerceIn(5, 480)
        val session = FocusSession(
            id = "focus-" + UUID.randomUUID().toString().take(8),
            goal = cleanGoal,
            startedAtMs = nowMs,
            endsAtMs = nowMs + mins * 60_000L,
            distractionPackages = distractionPackages.ifEmpty { config.watchPackages },
            autoBounce = autoBounce,
            conversationId = conversationId,
        )
        state = state.copy(activeFocus = session)
        dirty = true
        flush()
        return session
    }

    @Synchronized fun stopFocusSession(completed: Boolean = false, nowMs: Long = clock()): FocusSession? {
        val current = state.activeFocus ?: return null
        val elapsedMins = ((nowMs - current.startedAtMs).coerceAtLeast(0L) / 60_000L).toInt()
        if (completed || elapsedMins >= 1) {
            val day = dateKey(nowMs)
            val rec = state.days[day] ?: DailyHabitRecord(day)
            updateDay(rec.copy(focusCompletedMinutes = rec.focusCompletedMinutes + elapsedMins))
        }
        state = state.copy(activeFocus = null)
        dirty = true
        flush()
        return current
    }

    @Synchronized fun activeFocus(nowMs: Long = clock()): FocusSession? = activeFocusLocked(nowMs)

    @Synchronized fun saveInsight(
        category: String,
        summary: String,
        recommendation: String,
        sourceDays: Int = 7,
        nowMs: Long = clock(),
    ): BuddyInsight {
        val insight = BuddyInsight(
            id = "ins-" + UUID.randomUUID().toString().take(8),
            createdAtMs = nowMs,
            category = category.trim().lowercase().ifBlank { "habit" }.take(32),
            summary = summary.trim().take(400),
            recommendation = recommendation.trim().take(400),
            sourceDays = sourceDays.coerceIn(1, MAX_DAYS),
        )
        val next = (state.insights + insight).takeLast(MAX_INSIGHTS)
        state = state.copy(insights = next)
        dirty = true
        flush()
        return insight
    }

    @Synchronized fun synthesize(days: Int = 7, nowMs: Long = clock()): HabitSynthesis {
        // Flush any ongoing foreground session delta into today's live view
        val activePkg = currentPkg
        if (activePkg != null && currentEnteredAtMs > 0L) {
            val delta = (nowMs - currentEnteredAtMs).coerceIn(0L, MAX_SINGLE_SESSION_MS)
            if (delta >= 5_000L) {
                addForegroundDuration(activePkg, currentLabel, delta, nowMs)
                currentEnteredAtMs = nowMs
            }
        }
        val span = days.coerceIn(1, MAX_DAYS)
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone())
        val todayIso = today.toString()
        val series = (span - 1 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong()).toString()
            val rec = state.days[d]
            HabitDayPoint(
                dateIso = d,
                foregroundMinutes = ((rec?.totalForegroundMs ?: 0L) / 60_000L).toInt(),
                pickups = rec?.pickups ?: 0,
                scrollEvents = rec?.totalScrollEvents ?: 0,
                focusMinutes = rec?.focusCompletedMinutes ?: 0,
            )
        }
        val todayRec = state.days[todayIso] ?: DailyHabitRecord(todayIso)
        val topApps = todayRec.apps.values
            .sortedByDescending { it.foregroundMs + it.scrollEvents * 1_500L }
            .take(8)
        val watchedMs = todayRec.apps.values
            .filter { it.packageName in config.watchPackages }
            .sumOf { it.foregroundMs }
        val totalMs = todayRec.totalForegroundMs.coerceAtLeast(todayRec.apps.values.sumOf { it.foregroundMs })
        val watchedPct = if (totalMs > 0L) ((watchedMs * 100L) / totalMs).toInt().coerceIn(0, 100) else 0
        return HabitSynthesis(
            daysAnalyzed = span,
            todayDateIso = todayIso,
            todayForegroundMinutes = (totalMs / 60_000L).toInt(),
            todayPickups = todayRec.pickups,
            todayScrollEvents = todayRec.totalScrollEvents,
            todayDoomscrollEpisodes = todayRec.apps.values.sumOf { it.doomscrollEpisodes },
            todayFocusMinutes = todayRec.focusCompletedMinutes,
            todayNudgesSent = todayRec.nudgesSent,
            watchedSharePercent = watchedPct,
            topAppsToday = topApps,
            dailySeries = series,
            activeFocus = activeFocusLocked(nowMs),
            recentInsights = state.insights.takeLast(10).reversed(),
        )
    }

    @Synchronized fun flush() {
        if (!dirty) return
        saveAtomic(stateFile, json.encodeToString(state))
        dirty = false
    }

    private fun checkFocusDrift(packageName: String, appLabel: String, nowMs: Long): BuddyIntervention? {
        if (!config.enabled) return null
        val focus = activeFocusLocked(nowMs) ?: return null
        if (packageName !in focus.distractionPackages) return null
        if (!cooldownElapsed("focus:$packageName", nowMs)) return null

        lastNudgeByPkg["focus:$packageName"] = nowMs
        val updatedFocus = focus.copy(nudgesSent = focus.nudgesSent + 1)
        val day = dateKey(nowMs)
        val rec = state.days[day] ?: DailyHabitRecord(day)
        state = state.copy(activeFocus = updatedFocus)
        updateDay(rec.copy(nudgesSent = rec.nudgesSent + 1))
        flush()

        val rem = updatedFocus.remainingMinutes(nowMs)
        return BuddyIntervention.FocusDrift(
            packageName = packageName,
            appLabel = appLabel,
            focusGoal = updatedFocus.goal,
            remainingMinutes = rem,
            title = "Focus Guard · Get back to ${updatedFocus.goal}",
            message = "You opened $appLabel with ${rem}m left on \"${updatedFocus.goal}\". Tap to talk to Djini or snap back.",
            suggestedPrompt = "I drifted into $appLabel while focusing on \"${updatedFocus.goal}\" (${rem}m left). Give me a sharp accountability nudge and help me resume right now.",
            autoBounce = updatedFocus.autoBounce,
        )
    }

    private fun activeFocusLocked(nowMs: Long): FocusSession? {
        val current = state.activeFocus ?: return null
        if (!current.isActive(nowMs)) {
            val mins = ((current.endsAtMs - current.startedAtMs).coerceAtLeast(0L) / 60_000L).toInt()
            val day = dateKey(nowMs)
            val rec = state.days[day] ?: DailyHabitRecord(day)
            state = state.copy(activeFocus = null)
            updateDay(rec.copy(focusCompletedMinutes = rec.focusCompletedMinutes + mins))
            flush()
            return null
        }
        return current
    }

    private fun addForegroundDuration(packageName: String, appLabel: String, deltaMs: Long, nowMs: Long) {
        val day = dateKey(nowMs)
        val rec = state.days[day] ?: DailyHabitRecord(day)
        val prevApp = rec.apps[packageName] ?: AppDailyStats(packageName, appLabel)
        val nextApp = prevApp.copy(
            appLabel = appLabel.ifBlank { prevApp.appLabel },
            foregroundMs = prevApp.foregroundMs + deltaMs,
            lastSeenMs = nowMs,
        )
        updateDay(rec.copy(totalForegroundMs = rec.totalForegroundMs + deltaMs, apps = rec.apps + (packageName to nextApp)))
    }

    private fun cooldownElapsed(key: String, nowMs: Long): Boolean {
        val prev = lastNudgeByPkg[key] ?: return true
        return nowMs - prev >= config.nudgeCooldownSec.coerceAtLeast(10) * 1_000L
    }

    private fun updateDay(record: DailyHabitRecord) {
        val nextDays = (state.days + (record.dateIso to record))
            .entries.sortedByDescending { it.key }.take(MAX_DAYS).associate { it.toPair() }
        state = state.copy(days = nextDays)
        dirty = true
    }

    private fun dateKey(nowMs: Long): String =
        LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone()).toString()

    private fun loadConfig(): BuddyConfig = runCatching {
        if (configFile.isFile) json.decodeFromString<BuddyConfig>(configFile.readText()) else BuddyConfig()
    }.getOrDefault(BuddyConfig())

    private fun loadState(): BuddyLedgerState = runCatching {
        if (stateFile.isFile) json.decodeFromString<BuddyLedgerState>(stateFile.readText()) else BuddyLedgerState()
    }.getOrDefault(BuddyLedgerState())

    private fun saveAtomic(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private const val MAX_DAYS = 60
        private const val MAX_INSIGHTS = 100
        private const val MAX_CUSTOM_HAPTICS = 32
        private const val MAX_SINGLE_SESSION_MS = 30 * 60_000L
    }
}
