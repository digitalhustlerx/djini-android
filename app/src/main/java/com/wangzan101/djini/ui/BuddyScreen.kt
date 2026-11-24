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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.wangzan101.djini.buddy.BuddyRuntime
import com.wangzan101.djini.core.buddy.AppDailyStats
import com.wangzan101.djini.core.buddy.BuddyInsight
import com.wangzan101.djini.core.buddy.HabitDayPoint
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

    LaunchedEffect(Unit) {
        runtime.syncSystemUsageAsync()
    }

    PageScaffold("Djini Pulse", onBack) {
        // 1. Companion Status & Permissions
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

        // 4. Recursive Synthesis & Live Buddy Chat
        GroupLabel("RECURSIVE AI COACHING")
        DjiniCard(Modifier.padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Synthesize your on-device usage, scroll loops, pickups, and past insights so Djini learns your patterns recursively and coaches you.",
                    style = DjiniType.Caption,
                    color = c.ink2,
                )
                DjiniButton(
                    "Synthesize My Habits in Chat",
                    onClick = {
                        val id = runtime.launchSynthesisCoaching()
                        onOpenConversation(id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DjiniButton(
                    "Send Clickable Check-In Notification",
                    onClick = {
                        runtime.sendTestCheckInNotification()
                        Toast.makeText(context, "Sent Djini Pulse notification — tap it to jump into chat", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    kind = ButtonKind.Tonal,
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

        // 5. Programmable Haptics Studio
        GroupLabel("PROGRAMMABLE HAPTICS STUDIO")
        GroupCard {
            val patterns = cfg.allHaptics()
            patterns.forEachIndexed { idx, pattern ->
                ListRow(
                    DjiniIcons.Spark,
                    pattern.name,
                    "${pattern.description} (${pattern.timingsMs.sum()}ms)",
                    first = idx == 0,
                    onClick = { runtime.playPattern(pattern.id) },
                ) {
                    StatusPill("Feel", PillTone.Neutral)
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
