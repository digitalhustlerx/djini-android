package com.wangzan101.djini.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.djini.buddy.BuddyNightlyScheduler
import com.wangzan101.djini.buddy.BuddyRuntime
import com.wangzan101.djini.buddy.DjiniPulseWidgetProvider
import com.wangzan101.djini.core.buddy.AppDailyStats
import com.wangzan101.djini.core.buddy.BuddyInsight
import com.wangzan101.djini.core.buddy.HabitDayPoint
import com.wangzan101.djini.core.buddy.HapticPattern
import com.wangzan101.djini.ui.components.ButtonKind
import com.wangzan101.djini.ui.components.ButtonSize
import com.wangzan101.djini.ui.components.DjiniButton
import com.wangzan101.djini.ui.components.DjiniCard
import com.wangzan101.djini.ui.components.DjiniSwitch
import com.wangzan101.djini.ui.components.GroupCard
import com.wangzan101.djini.ui.components.GroupLabel
import com.wangzan101.djini.ui.components.ListRow
import com.wangzan101.djini.ui.components.PageScaffold
import com.wangzan101.djini.ui.components.PillTone
import com.wangzan101.djini.ui.components.SelectChip
import com.wangzan101.djini.ui.components.StatusPill
import com.wangzan101.djini.ui.theme.DjiniIcons
import com.wangzan101.djini.ui.theme.DjiniTheme
import com.wangzan101.djini.ui.theme.DjiniType

private data class HapticBeat(
    val pauseMs: Int,
    val buzzMs: Int,
    val amplitude: Int,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuddyScreen(
    runtime: BuddyRuntime,
    onFixAccessibility: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snap by runtime.snapshot.collectAsState()
    val cfg = snap.config
    val synth = snap.synthesis
    val c = DjiniTheme.colors

    var focusGoal by remember { mutableStateOf("") }
    var focusDuration by remember { mutableIntStateOf(45) }
    var vipDraft by remember(cfg.vipKeywords) { mutableStateOf(cfg.vipKeywords.joinToString(", ")) }

    // Visual Haptic Waveform Composer state
    var composerOpen by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var draftDesc by remember { mutableStateOf("") }
    val beats = remember {
        mutableStateListOf(
            HapticBeat(pauseMs = 0, buzzMs = 60, amplitude = 160),
            HapticBeat(pauseMs = 70, buzzMs = 110, amplitude = 230),
        )
    }

    LaunchedEffect(Unit) {
        runtime.syncSystemUsageAsync()
    }

    PageScaffold("Djini Pulse", onBack) {
        // 1. Companion Status, Permissions & Home-Screen Widget
        GroupLabel("ACTIVE COMPANION")
        GroupCard {
            ListRow(
                DjiniIcons.Ripple,
                "Djini Pulse (24/7 Habit Buddy)",
                "Watches screen habits, scroll loops, focus drift & VIP alerts locally on device",
                first = true,
            ) {
                DjiniSwitch(cfg.enabled, runtime::setEnabled)
            }
            ListRow(
                DjiniIcons.Bell,
                "Dynamic Haptic Feedback",
                if (snap.hasHapticsHardware) "Programmable vibration patterns on your phone motor" else "Vibration motor unavailable",
            ) {
                DjiniSwitch(cfg.hapticsEnabled, runtime::setHapticsEnabled, enabled = snap.hasHapticsHardware)
            }
            ListRow(
                DjiniIcons.Chart,
                "Usage Access (Screen Time)",
                if (snap.hasUsageAccess) "Connected to Android Digital Wellbeing stats" else "Tap to grant full per-app screen time tracking",
                onClick = if (snap.hasUsageAccess) ({ runtime.syncSystemUsageAsync() }) else ({ runtime.usage.openUsageAccessSettings() }),
            ) {
                StatusPill(if (snap.hasUsageAccess) "Granted" else "Grant", if (snap.hasUsageAccess) PillTone.Ok else PillTone.Warn)
            }
            ListRow(
                DjiniIcons.Phone,
                "Accessibility Sensor (Scroll & Focus)",
                if (snap.hasAccessibility) "Live scroll-loop detection and focus bounce ready" else "Tap to enable real-time scroll & focus guard",
                onClick = onFixAccessibility,
            ) {
                StatusPill(if (snap.hasAccessibility) "Active" else "Enable", if (snap.hasAccessibility) PillTone.Ok else PillTone.Warn)
            }
            ListRow(
                DjiniIcons.Spark,
                "Home-Screen Live Widget",
                "Pin live screen time, pickups, 1-tap Focus Guard & Pulse Feel to your launcher",
                onClick = {
                    val pinned = DjiniPulseWidgetProvider.requestPin(context)
                    val msg = if (pinned) {
                        "Choose where to place your Djini Pulse widget"
                    } else {
                        "Long-press your home screen → Widgets → Djini Pulse"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                },
            ) {
                StatusPill("Pin", PillTone.Accent)
            }
        }

        // 2. Today's Visual Habit Metrics
        GroupLabel("TODAY'S DIGITAL PULSE")
        DjiniCard(Modifier.padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Screen Time", formatMins(synth.todayForegroundMinutes), Modifier.weight(1f))
                    MetricTile("Pickups", synth.todayPickups.toString(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Scroll Bursts", "${synth.todayScrollEvents} (${synth.todayDoomscrollEpisodes} loops)", Modifier.weight(1f))
                    MetricTile("Focus Mins", "${synth.todayFocusMinutes}m", Modifier.weight(1f))
                }
                if (synth.watchedSharePercent > 0) {
                    Text(
                        "Watched distraction apps make up ${synth.watchedSharePercent}% of today's tracked screen time.",
                        style = DjiniType.Caption,
                        color = if (synth.watchedSharePercent >= 45) c.warn else c.ink2,
                    )
                }
                WeeklyBarChart(synth.dailySeries)
                if (synth.topAppsToday.isNotEmpty()) {
                    Text("Top Apps Today", style = DjiniType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                    val maxMs = synth.topAppsToday.maxOf { it.foregroundMs.coerceAtLeast(1L) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        synth.topAppsToday.take(5).forEach { app ->
                            AppUsageBar(app, maxMs, watched = app.packageName in cfg.watchPackages)
                        }
                    }
                } else {
                    Text(
                        "Activity appears here automatically as you use your phone or once Usage Access is granted.",
                        style = DjiniType.Caption,
                        color = c.ink3,
                    )
                }
            }
        }

        // 3. Focus Guardian
        GroupLabel("FOCUS GUARDIAN")
        DjiniCard(Modifier.padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val active = synth.activeFocus
                if (active != null) {
                    val remaining = active.remainingMinutes(System.currentTimeMillis())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(active.goal, style = DjiniType.BodyStrong, color = c.ink)
                            Text(
                                "${remaining}m remaining · ${active.nudgesSent} nudges · Auto-bounce ${if (active.autoBounce) "ON" else "OFF"}",
                                style = DjiniType.Caption,
                                color = c.ink2,
                            )
                        }
                        StatusPill("Guarding", PillTone.Ok)
                    }
                    DjiniButton(
                        "Complete Focus Session",
                        onClick = { runtime.stopFocus(completed = true) },
                        modifier = Modifier.fillMaxWidth(),
                        kind = ButtonKind.Primary,
                    )
                } else {
                    OutlinedTextField(
                        value = focusGoal,
                        onValueChange = { focusGoal = it },
                        label = { Text("What are you working on?") },
                        placeholder = { Text("e.g. Ship feature, Deep study, No Reels") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(25, 45, 90).forEach { mins ->
                            SelectChip("${mins}m", focusDuration == mins, { focusDuration = mins })
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Auto-bounce distraction apps", style = DjiniType.Body, color = c.ink)
                            Text("Gently return to Home + vibrate if you open a distraction app", style = DjiniType.Caption, color = c.ink2)
                        }
                        DjiniSwitch(cfg.autoBounceOnFocusDrift, runtime::setAutoBounceDefault)
                    }
                    DjiniButton(
                        "Start ${focusDuration}m Focus Guard",
                        onClick = {
                            runtime.startFocus(
                                goal = focusGoal.ifBlank { "Deep Focus" },
                                durationMinutes = focusDuration,
                                autoBounce = cfg.autoBounceOnFocusDrift,
                            )
                            Toast.makeText(context, "Focus Guard active for ${focusDuration}m", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // 4. Nightly Auto-Synthesis & Recursive Coaching
        GroupLabel("NIGHTLY AUTO-BRIEFING & COACHING")
        DjiniCard(Modifier.padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Scheduled Nightly Synthesis", style = DjiniType.BodyStrong, color = c.ink)
                        val scheduleNote = if (cfg.nightlyBriefEnabled) {
                            val last = cfg.lastNightlyBriefDate?.let { " · Last: $it" }.orEmpty()
                            "Daily at ${BuddyNightlyScheduler.formatTime(cfg.nightlyBriefHour, cfg.nightlyBriefMinute)}$last"
                        } else {
                            "Off — enable for automatic evening habit synthesis & brief"
                        }
                        Text(scheduleNote, style = DjiniType.Caption, color = c.ink2)
                    }
                    DjiniSwitch(cfg.nightlyBriefEnabled, { runtime.setNightlyBrief(it) })
                }
                if (cfg.nightlyBriefEnabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(20 to 30, 21 to 30, 22 to 30, 23 to 0).forEach { (h, m) ->
                            val selected = cfg.nightlyBriefHour == h && cfg.nightlyBriefMinute == m
                            SelectChip(
                                BuddyNightlyScheduler.formatTime(h, m),
                                selected,
                                { runtime.setNightlyBrief(true, h, m) },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DjiniButton(
                        "Run Nightly Brief Now",
                        onClick = {
                            runtime.runNightlyBriefAsync(fromAlarm = false)
                            Toast.makeText(context, "Synthesizing tonight's Pulse brief…", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        kind = ButtonKind.Primary,
                        size = ButtonSize.Small,
                    )
                    DjiniButton(
                        "Check-In Ping",
                        onClick = {
                            runtime.sendTestCheckInNotification()
                            Toast.makeText(context, "Sent clickable Djini Pulse notification", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        kind = ButtonKind.Tonal,
                        size = ButtonSize.Small,
                    )
                }
                DjiniButton(
                    "Deep AI Habit Synthesis in Chat",
                    onClick = {
                        val id = runtime.launchSynthesisCoaching()
                        onOpenConversation(id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary,
                )
                if (synth.recentInsights.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Stored Recursive Insights", style = DjiniType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                    synth.recentInsights.take(5).forEach { insight ->
                        InsightRow(insight)
                    }
                }
            }
        }

        // 5. Programmable Haptics Studio + Visual Waveform Composer
        GroupLabel("PROGRAMMABLE HAPTICS STUDIO")
        DjiniCard(Modifier.padding(bottom = 12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val patterns = cfg.allHaptics()
                patterns.forEach { pattern ->
                    HapticPatternRow(
                        pattern = pattern,
                        onPlay = { runtime.playPattern(pattern.id) },
                        onDelete = if (!pattern.builtIn) ({ runtime.deleteCustomPattern(pattern.id) }) else null,
                    )
                }

                DjiniButton(
                    if (composerOpen) "Hide Waveform Composer" else "+ Compose Custom Haptic Signature",
                    onClick = { composerOpen = !composerOpen },
                    modifier = Modifier.fillMaxWidth(),
                    kind = if (composerOpen) ButtonKind.Secondary else ButtonKind.Tonal,
                    size = ButtonSize.Small,
                )

                if (composerOpen) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.fill)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Visual Haptic Waveform Composer", style = DjiniType.BodyStrong, color = c.ink)
                        Text("Quick rhythm templates", style = DjiniType.Micro, color = c.ink2)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "Double Tap" to listOf(HapticBeat(0, 45, 160), HapticBeat(70, 55, 225)),
                                "Ramp Up" to listOf(HapticBeat(0, 50, 95), HapticBeat(60, 75, 175), HapticBeat(60, 120, 255)),
                                "Calm Wave" to listOf(HapticBeat(0, 180, 90), HapticBeat(160, 260, 145)),
                                "Morse Alert" to listOf(HapticBeat(0, 45, 230), HapticBeat(45, 45, 230), HapticBeat(45, 135, 255)),
                            ).forEach { (tplName, tplBeats) ->
                                SelectChip(tplName, false, {
                                    beats.clear()
                                    beats.addAll(tplBeats)
                                    if (draftName.isBlank()) draftName = tplName
                                })
                            }
                        }

                        // Live visual waveform preview
                        val previewTimings = beatsToTimings(beats)
                        val previewAmps = beatsToAmps(beats)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Waveform Preview · ${beats.size} beats · ${previewTimings.sum()}ms total",
                                style = DjiniType.Micro.copy(fontWeight = FontWeight.SemiBold),
                                color = c.ink2,
                            )
                            WaveformBars(previewTimings, previewAmps, heightDp = 42)
                        }

                        // Per-beat editors
                        beats.forEachIndexed { idx, beat ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(c.bg)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Beat ${idx + 1} · buzz ${beat.buzzMs}ms · power ${(beat.amplitude * 100) / 255}%",
                                        style = DjiniType.Caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = c.ink,
                                    )
                                    if (beats.size > 1) {
                                        Text(
                                            "Remove",
                                            style = DjiniType.Micro.copy(fontWeight = FontWeight.SemiBold),
                                            color = c.warn,
                                            modifier = Modifier.clickable { beats.removeAt(idx) }.padding(horizontal = 4.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (idx > 0) {
                                    Text("Gap before beat: ${beat.pauseMs}ms", style = DjiniType.Micro, color = c.ink2)
                                    Slider(
                                        value = beat.pauseMs.toFloat(),
                                        onValueChange = { beats[idx] = beat.copy(pauseMs = it.toInt()) },
                                        valueRange = 20f..400f,
                                    )
                                }
                                Text("Pulse duration: ${beat.buzzMs}ms", style = DjiniType.Micro, color = c.ink2)
                                Slider(
                                    value = beat.buzzMs.toFloat(),
                                    onValueChange = { beats[idx] = beat.copy(buzzMs = it.toInt()) },
                                    valueRange = 20f..400f,
                                )
                                Text("Intensity: ${beat.amplitude}/255", style = DjiniType.Micro, color = c.ink2)
                                Slider(
                                    value = beat.amplitude.toFloat(),
                                    onValueChange = { beats[idx] = beat.copy(amplitude = it.toInt()) },
                                    valueRange = 30f..255f,
                                )
                            }
                        }

                        if (beats.size < 8) {
                            DjiniButton(
                                "+ Add Beat (${beats.size}/8)",
                                onClick = { beats.add(HapticBeat(pauseMs = 70, buzzMs = 80, amplitude = 190)) },
                                modifier = Modifier.fillMaxWidth(),
                                kind = ButtonKind.Secondary,
                                size = ButtonSize.Small,
                            )
                        }

                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { draftName = it },
                            label = { Text("Signature Name") },
                            placeholder = { Text("e.g. Morning Kickstart") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draftDesc,
                            onValueChange = { draftDesc = it },
                            label = { Text("When Djini should use it") },
                            placeholder = { Text("e.g. Gentle cue when starting deep work") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DjiniButton(
                                "Preview Feel",
                                onClick = {
                                    runCatching {
                                        runtime.playCustomWaveform(beatsToTimings(beats), beatsToAmps(beats))
                                    }.onFailure {
                                        Toast.makeText(context, it.message ?: "Invalid waveform", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                kind = ButtonKind.Tonal,
                                size = ButtonSize.Small,
                            )
                            DjiniButton(
                                "Save Signature",
                                onClick = {
                                    val cleanName = draftName.trim().ifBlank { "Custom Pulse" }
                                    val slug = cleanName.lowercase()
                                        .replace(Regex("[^a-z0-9]+"), "_")
                                        .trim('_')
                                        .ifBlank { "custom_pulse" }
                                        .take(36)
                                    val pattern = runCatching {
                                        HapticPattern(
                                            id = slug,
                                            name = cleanName.take(60),
                                            description = draftDesc.trim().ifBlank { "Custom user haptic signature" }.take(200),
                                            timingsMs = beatsToTimings(beats),
                                            amplitudes = beatsToAmps(beats),
                                            builtIn = false,
                                        )
                                    }
                                    pattern.onSuccess { p ->
                                        runtime.saveCustomPattern(p)
                                        runtime.playPattern(p.id)
                                        draftName = ""
                                        draftDesc = ""
                                        composerOpen = false
                                        Toast.makeText(context, "Saved \"${p.name}\" (available to Djini AI too)", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(context, it.message ?: "Could not save pattern", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                kind = ButtonKind.Primary,
                                size = ButtonSize.Small,
                            )
                        }
                    }
                }
            }
        }

        // 6. Guardrails, Coaching Tone & VIP Email Radar
        GroupLabel("BEHAVIOR & VIP ALERT RADAR")
        DjiniCard(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Coaching Tone", style = DjiniType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("direct" to "Direct", "warm" to "Warm", "coach" to "Coach").forEach { (id, label) ->
                        SelectChip(label, cfg.personalityTone == id, { runtime.setPersonalityTone(id) })
                    }
                }
                Text("Doomscroll Sensitivity (scrolls / min)", style = DjiniType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15 to "Strict (15)", 28 to "Balanced (28)", 45 to "Relaxed (45)").forEach { (v, label) ->
                        SelectChip(label, cfg.doomscrollScrollThreshold == v, { runtime.setDoomscrollThreshold(v) })
                    }
                }
                OutlinedTextField(
                    value = vipDraft,
                    onValueChange = { vipDraft = it },
                    label = { Text("VIP Email / Notification Keywords") },
                    supportingText = { Text("Comma-separated words that trigger Urgent Alert haptics & clickable chat cards") },
                    modifier = Modifier.fillMaxWidth(),
                )
                DjiniButton(
                    "Save VIP Keywords",
                    onClick = {
                        runtime.updateVipKeywords(vipDraft.split(","))
                        runtime.playPattern("gentle_tap")
                        Toast.makeText(context, "VIP radar keywords updated", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
}

private fun beatsToTimings(beats: List<HapticBeat>): List<Long> = buildList {
    beats.forEachIndexed { idx, b ->
        add(if (idx == 0) 0L else b.pauseMs.toLong().coerceIn(10L, 1_500L))
        add(b.buzzMs.toLong().coerceIn(15L, 1_500L))
    }
}

private fun beatsToAmps(beats: List<HapticBeat>): List<Int> = buildList {
    beats.forEach { b ->
        add(0)
        add(b.amplitude.coerceIn(1, 255))
    }
}

@Composable
private fun HapticPatternRow(
    pattern: HapticPattern,
    onPlay: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val c = DjiniTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.fill)
            .clickable(onClick = onPlay)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(pattern.name, style = DjiniType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${pattern.description} (${pattern.timingsMs.sum()}ms)",
                    style = DjiniType.Micro,
                    color = c.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    Text(
                        "Del",
                        style = DjiniType.Micro.copy(fontWeight = FontWeight.SemiBold),
                        color = c.warn,
                        modifier = Modifier.clickable(onClick = onDelete).padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
                StatusPill("Feel", PillTone.Accent)
            }
        }
        WaveformBars(pattern.timingsMs, pattern.amplitudes, heightDp = 24)
    }
}

@Composable
private fun WaveformBars(timingsMs: List<Long>, amplitudes: List<Int>, heightDp: Int) {
    val c = DjiniTheme.colors
    val steps = timingsMs.zip(amplitudes).filter { (t, _) -> t > 0L }
    if (steps.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().height(heightDp.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        steps.forEach { (durationMs, amp) ->
            val weight = durationMs.toFloat().coerceIn(20f, 350f)
            val isBuzz = amp > 0
            val heightFrac = if (isBuzz) (amp.toFloat() / 255f).coerceIn(0.22f, 1f) else 0.12f
            Box(
                Modifier
                    .weight(weight)
                    .height((heightDp * heightFrac).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isBuzz) c.accent else c.greySoft),
            )
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val c = DjiniTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.fill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = DjiniType.Micro, color = c.ink2)
        Text(value, style = DjiniType.Title.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WeeklyBarChart(series: List<HabitDayPoint>) {
    val c = DjiniTheme.colors
    val maxMins = (series.maxOfOrNull { it.foregroundMinutes } ?: 0).coerceAtLeast(15)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("7-Day Screen Time", style = DjiniType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        Row(
            Modifier.fillMaxWidth().height(86.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            series.forEach { day ->
                val frac = (day.foregroundMinutes.toFloat() / maxMins.toFloat()).coerceIn(0.08f, 1f)
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        if (day.foregroundMinutes > 0) "${day.foregroundMinutes}m" else "0",
                        style = DjiniType.Micro,
                        color = c.ink3,
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.65f)
                            .height((54f * frac).dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (day.foregroundMinutes > 0) c.accent else c.fill),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(day.dateIso.takeLast(2), style = DjiniType.Micro, color = c.ink2)
                }
            }
        }
    }
}

@Composable
private fun AppUsageBar(app: AppDailyStats, maxMs: Long, watched: Boolean) {
    val c = DjiniTheme.colors
    val mins = (app.foregroundMs / 60_000L).toInt()
    val frac = (app.foregroundMs.toFloat() / maxMs.toFloat()).coerceIn(0.05f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                app.appLabel.ifBlank { app.packageName } + if (watched) " · Watched" else "",
                style = DjiniType.Caption,
                color = if (watched) c.warn else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text("${mins}m · ${app.scrollEvents} scrolls", style = DjiniType.Micro, color = c.ink2)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)).background(c.fill)) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (watched) c.warn else c.accent),
            )
        }
    }
}

@Composable
private fun InsightRow(insight: BuddyInsight) {
    val c = DjiniTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.fill)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(insight.category.uppercase(), style = DjiniType.Micro.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
        Text(insight.summary, style = DjiniType.Body, color = c.ink)
        if (insight.recommendation.isNotBlank()) {
            Text("Next: ${insight.recommendation}", style = DjiniType.Caption, color = c.ink2)
        }
    }
}

private fun formatMins(mins: Int): String =
    if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
