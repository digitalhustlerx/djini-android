package com.wangzan101.djini.buddy

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.wangzan101.djini.DjiniApp
import com.wangzan101.djini.R
import com.wangzan101.djini.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.djini.core.agent.Turn
import com.wangzan101.djini.core.buddy.BuddyConfig
import com.wangzan101.djini.core.buddy.BuddyInsight
import com.wangzan101.djini.core.buddy.BuddyIntervention
import com.wangzan101.djini.core.buddy.FocusSession
import com.wangzan101.djini.core.buddy.HabitLedger
import com.wangzan101.djini.core.buddy.HabitSynthesis
import com.wangzan101.djini.core.buddy.HapticPattern
import com.wangzan101.djini.core.memory.MemoryKind
import com.wangzan101.djini.core.memory.MemorySource
import com.wangzan101.djini.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class BuddySnapshot(
    val config: BuddyConfig,
    val synthesis: HabitSynthesis,
    val hasUsageAccess: Boolean,
    val hasAccessibility: Boolean,
    val hasHapticsHardware: Boolean,
)

/**
 * Always-on Djini Pulse runtime: lightweight 24/7 digital habit sensor, doomscroll & focus
 * guardian, VIP notification/email radar, dynamic haptic actuator, and clickable-to-chat
 * proactive check-in notifier.
 */
class BuddyRuntime(private val app: DjiniApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ledger = HabitLedger(File(app.filesDir, "buddy"))
    val haptics = HapticBehaviorEngine(app)
    val usage = AndroidUsageCollector(app)

    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<BuddySnapshot> = _snapshot.asStateFlow()

    private var started = false
    private var flushJob: Job? = null
    private val vipRecentKeys = LinkedHashMap<String, Long>()
    private var notifSeq = ID_BUDDY_FIRST

    private val pickupReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT && ledger.config().enabled) {
                ledger.recordPickup()
                scheduleFlush()
                refresh()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        ensureChannel()
        runCatching {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(pickupReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(pickupReceiver, filter)
            }
        }.onFailure { Log.w(TAG, "Could not register pickup receiver", it) }
        syncSystemUsageAsync()
    }

    fun refresh() {
        _snapshot.value = buildSnapshot()
    }

    fun syncSystemUsageAsync() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val todayMap = usage.collectTodayUsage()
                if (todayMap.isNotEmpty()) {
                    val todayIso = LocalDate.now(ZoneId.systemDefault()).toString()
                    ledger.mergeSystemUsage(todayIso, todayMap)
                    ledger.flush()
                }
            }.onFailure { Log.w(TAG, "Usage sync failed", it) }
            refresh()
        }
    }

    fun setEnabled(enabled: Boolean) {
        ledger.updateConfig { it.copy(enabled = enabled) }
        if (enabled) {
            playPattern("gentle_tap")
            syncSystemUsageAsync()
        }
        refresh()
    }

    fun setHapticsEnabled(enabled: Boolean) {
        ledger.updateConfig { it.copy(hapticsEnabled = enabled) }
        if (enabled) playPattern("gentle_tap")
        refresh()
    }

    fun setDoomscrollThreshold(scrolls: Int) {
        ledger.updateConfig { it.copy(doomscrollScrollThreshold = scrolls.coerceIn(5, 120)) }
        refresh()
    }

    fun setPersonalityTone(tone: String) {
        val clean = tone.trim().lowercase().takeIf { it in setOf("direct", "warm", "coach") } ?: "direct"
        ledger.updateConfig { it.copy(personalityTone = clean) }
        refresh()
    }

    fun setAutoBounceDefault(autoBounce: Boolean) {
        ledger.updateConfig { it.copy(autoBounceOnFocusDrift = autoBounce) }
        refresh()
    }

    fun toggleWatchPackage(packageName: String) {
        ledger.updateConfig { cfg ->
            val next = if (packageName in cfg.watchPackages) cfg.watchPackages - packageName else cfg.watchPackages + packageName
            cfg.copy(watchPackages = next)
        }
        refresh()
    }

    fun updateVipKeywords(keywords: List<String>) {
        val cleaned = keywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().take(40)
        ledger.updateConfig { it.copy(vipKeywords = cleaned) }
        refresh()
    }

    fun startFocus(
        goal: String,
        durationMinutes: Int,
        distractionPackages: Set<String> = ledger.config().watchPackages,
        autoBounce: Boolean = ledger.config().autoBounceOnFocusDrift,
        conversationId: String? = null,
    ): FocusSession {
        val session = ledger.startFocusSession(goal, durationMinutes, distractionPackages, autoBounce, conversationId)
        playPattern("heartbeat")
        refresh()
        return session
    }

    fun stopFocus(completed: Boolean = true): FocusSession? {
        val stopped = ledger.stopFocusSession(completed = completed)
        if (stopped != null && completed) playPattern("focus_win")
        refresh()
        return stopped
    }

    fun playPattern(patternId: String): Boolean {
        val cfg = ledger.config()
        if (!cfg.hapticsEnabled) return false
        val pattern = cfg.hapticById(patternId) ?: return false
        return haptics.play(pattern)
    }

    fun playCustomWaveform(timingsMs: List<Long>, amplitudes: List<Int>): Boolean {
        if (!ledger.config().hapticsEnabled) return false
        val validated = HapticPattern("adhoc", "Ad-hoc", "", timingsMs, amplitudes)
        return haptics.play(validated)
    }

    fun saveCustomPattern(pattern: HapticPattern): HapticPattern {
        val saved = ledger.saveHapticPattern(pattern)
        refresh()
        return saved
    }

    fun saveInsight(category: String, summary: String, recommendation: String, sourceDays: Int = 7): BuddyInsight {
        val insight = ledger.saveInsight(category, summary, recommendation, sourceDays)
        // Mirror high-value synthesized insights into Djini's long-term MemoryStore when memory is enabled
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (app.memory.settings().switches.value.useMemory) {
                    val content = "[Djini Pulse · ${insight.category}] ${insight.summary} — ${insight.recommendation}"
                    val source = MemorySource("buddy_synthesis", insight.summary, "buddy-pulse", insight.createdAtMs)
                    app.memory.store().add(MemoryKind.preference, content, listOf("djini-pulse", insight.category), source)
                }
            }.onFailure { Log.w(TAG, "Could not mirror insight into memory store", it) }
        }
        refresh()
        return insight
    }

    /**
     * Lightweight real-time hook from [ScreenAccessibility.onAccessibilityEvent].
     * Never copies private screen text; only inspects package transitions & scroll ticks.
     */
    fun onAccessibilityEvent(eventType: Int, packageName: String?) {
        val pkg = packageName?.takeIf { it.isNotBlank() && it != app.packageName && it != "com.android.systemui" } ?: return
        if (!ledger.config().enabled) return
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val label = usage.appLabel(pkg)
                val intervention = ledger.onForegroundApp(pkg, label)
                scheduleFlush()
                if (intervention != null) handleIntervention(intervention)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val label = usage.appLabel(pkg)
                val intervention = ledger.onScroll(pkg, label)
                scheduleFlush()
                if (intervention != null) handleIntervention(intervention)
            }
        }
    }

    /**
     * Real-time hook from [com.wangzan101.djini.proactive.NoticeListenerService].
     * Detects VIP emails or urgent messages matching [BuddyConfig.vipKeywords] and triggers
     * an expressive haptic signature + clickable-to-chat notification.
     */
    fun onNotificationPosted(sbn: StatusBarNotification) {
        val cfg = ledger.config()
        if (!cfg.enabled || cfg.vipKeywords.isEmpty()) return
        val pkg = sbn.packageName ?: return
        if (pkg == app.packageName) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val combined = "$title $body".trim()
        if (combined.isBlank()) return

        val lower = combined.lowercase()
        val matched = cfg.vipKeywords.firstOrNull { kw -> kw.isNotBlank() && lower.contains(kw) } ?: return
        val now = System.currentTimeMillis()
        synchronized(vipRecentKeys) {
            val dedupKey = "$pkg:${title.take(60)}:${body.take(80)}"
            val prev = vipRecentKeys[dedupKey]
            if (prev != null && now - prev < 5 * 60_000L) return
            vipRecentKeys[dedupKey] = now
            if (vipRecentKeys.size > 120) {
                vipRecentKeys.entries.iterator().let { it.next(); it.remove() }
            }
        }

        val appLabel = usage.appLabel(pkg)
        playPattern("urgent_alert")
        val threadId = seedBuddyConversation(
            title = "VIP Alert · $appLabel",
            userNote = "Incoming notification matched \"$matched\" from $appLabel",
            buddyMessage = "**Important $appLabel alert** (matched `$matched`):\n> **${title.ifBlank { appLabel }}**\n> ${body.take(280)}\n\nReply here if you want me to read the full email/message, draft a response, or set a reminder.",
        )
        postClickableNotification(
            title = "Djini VIP · $appLabel ($matched)",
            body = "${title.ifBlank { appLabel }}: ${body.take(140)}",
            threadId = threadId,
        )
    }

    /**
     * Sends an interactive check-in notification right now (for testing or routine triggers)
     * that opens straight into a live Djini Pulse conversation when tapped.
     */
    fun sendTestCheckInNotification(): String {
        playPattern("heartbeat")
        val synth = ledger.synthesize(7)
        val topLine = synth.topAppsToday.take(3).joinToString(", ") {
            "${it.appLabel.ifBlank { it.packageName }} (${(it.foregroundMs / 60_000L)}m)"
        }.ifBlank { "just starting to collect today's activity" }
        val threadId = seedBuddyConversation(
            title = "Djini Pulse Check-In",
            userNote = "Active companion check-in",
            buddyMessage = "Hey — Djini Pulse is live on your phone.\n\nToday so far: **${synth.todayForegroundMinutes}m** screen time, **${synth.todayPickups}** pickups, **${synth.todayScrollEvents}** scroll events ($topLine).\n\nTell me what you're working on and I'll guard your focus with custom haptics, or ask me to synthesize your habits.",
        )
        postClickableNotification(
            title = "Djini Pulse · Ready to keep you sharp",
            body = "Today: ${synth.todayForegroundMinutes}m screen · ${synth.todayPickups} pickups. Tap to open chat.",
            threadId = threadId,
        )
        return threadId
    }

    /**
     * Creates a fresh Buddy Coaching conversation and immediately triggers a real Djini agent run
     * that synthesizes all on-device habit analytics, compares against prior insights, saves new
     * recursive insights, and plays a subtle haptic check-in.
     */
    fun launchSynthesisCoaching(): String {
        val conversationId = "buddy-" + UUID.randomUUID().toString().take(8)
        app.conversations.setTitle(conversationId, "Djini Pulse · Habit Synthesis")
        val prompt = "Read my digital habit analytics via buddy_habit_stats (last 7 days), synthesize my top phone habits, " +
            "doomscrolling patterns, pickups, and focus progress, save 1–2 concise recursive insights via buddy_insight_save, " +
            "play a gentle_tap haptic to check in, and give me a crisp, actionable coaching brief with one routine or focus session suggestion."
        TaskService.start(app, prompt, conversationId)
        return conversationId
    }

    private fun handleIntervention(intervention: BuddyIntervention) {
        playPattern(intervention.hapticPatternId)
        val threadId = seedBuddyConversation(
            title = intervention.title,
            userNote = "Automatic Djini Pulse check-in (${intervention.appLabel})",
            buddyMessage = "${intervention.message}\n\nReply here to talk through it, start/adjust a Focus Guard session, or tell me to take a timed break.",
        )
        postClickableNotification(intervention.title, intervention.message, threadId)
        if (intervention.autoBounce) {
            scope.launch(Dispatchers.Main) {
                delay(700L)
                runCatching {
                    ScreenAccessibility.instance.value?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }.onFailure { Log.w(TAG, "Auto-bounce home failed", it) }
            }
        }
        refresh()
    }

    private fun seedBuddyConversation(title: String, userNote: String, buddyMessage: String): String {
        val threadId = "pulse-" + LocalDate.now(ZoneId.systemDefault()).toString() + "-" + UUID.randomUUID().toString().take(6)
        app.conversations.setTitle(threadId, title.take(60))
        app.conversations.append(
            threadId,
            Turn(
                userText = userNote,
                outcome = buddyMessage,
                status = "succeeded",
                atMs = System.currentTimeMillis(),
            ),
        )
        return threadId
    }

    private fun postClickableNotification(title: String, body: String, threadId: String) {
        ensureChannel()
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val openIntent = TaskService.openApp(app, threadId)
        val notification = Notification.Builder(app, CHANNEL_BUDDY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        val id = notifSeq++
        if (notifSeq > ID_BUDDY_FIRST + 40) notifSeq = ID_BUDDY_FIRST
        runCatching { nm.notify(id, notification) }
            .onFailure { Log.w(TAG, "Could not post buddy notification", it) }
    }

    private fun ensureChannel() {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_BUDDY) != null) return
        val ch = NotificationChannel(
            CHANNEL_BUDDY,
            "Djini Pulse (Active Buddy)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Proactive habit check-ins, focus nudges, and VIP alerts that open directly into Djini chat."
            enableVibration(false) // Djini drives its own dynamic haptic patterns via HapticBehaviorEngine
        }
        nm.createNotificationChannel(ch)
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            delay(5_000L)
            ledger.flush()
            refresh()
        }
    }

    private fun buildSnapshot(): BuddySnapshot = BuddySnapshot(
        config = ledger.config(),
        synthesis = ledger.synthesize(7),
        hasUsageAccess = usage.hasUsageAccess(),
        hasAccessibility = ScreenAccessibility.instance.value != null,
        hasHapticsHardware = haptics.hasHardware(),
    )

    companion object {
        private const val TAG = "DjiniBuddy"
        const val CHANNEL_BUDDY = "djini_pulse_buddy"
        private const val ID_BUDDY_FIRST = 9400
    }
}
