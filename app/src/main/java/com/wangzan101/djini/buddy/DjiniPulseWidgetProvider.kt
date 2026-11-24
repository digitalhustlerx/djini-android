package com.wangzan101.djini.buddy

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import com.wangzan101.djini.DjiniApp
import com.wangzan101.djini.R
import com.wangzan101.djini.ui.MainActivity

/**
 * Live Android home-screen widget for Djini Pulse.
 * Shows live screen time, pickups, focus/scroll status, and offers instant 1-tap Focus Guard
 * toggle, live haptic pulse check-in, and direct jump into the Djini Pulse dashboard.
 */
class DjiniPulseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext as? DjiniApp ?: return
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, app))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val app = context.applicationContext as? DjiniApp ?: return
        when (intent.action) {
            ACTION_TOGGLE_FOCUS -> {
                val active = app.buddy.ledger.activeFocus()
                if (active != null) {
                    app.buddy.stopFocus(completed = true)
                } else {
                    app.buddy.startFocus(
                        goal = "Deep Focus",
                        durationMinutes = 45,
                        autoBounce = app.buddy.ledger.config().autoBounceOnFocusDrift,
                    )
                }
                refreshAll(context)
            }
            ACTION_PULSE_CHECK -> {
                app.buddy.playPattern("heartbeat")
                app.buddy.syncSystemUsageAsync()
                refreshAll(context)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_FOCUS = "com.wangzan101.djini.widget.ACTION_TOGGLE_FOCUS"
        const val ACTION_PULSE_CHECK = "com.wangzan101.djini.widget.ACTION_PULSE_CHECK"

        fun refreshAll(context: Context) {
            val app = context.applicationContext as? DjiniApp ?: return
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, DjiniPulseWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, app)
            ids.forEach { id -> mgr.updateAppWidget(id, views) }
        }

        fun requestPin(context: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(context) ?: return false
            if (!mgr.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(context, DjiniPulseWidgetProvider::class.java)
            return mgr.requestPinAppWidget(provider, null, null)
        }

        private fun buildViews(context: Context, app: DjiniApp): RemoteViews {
            val snap = app.buddy.snapshot.value
            val synth = snap.synthesis
            val active = synth.activeFocus
            val rv = RemoteViews(context.packageName, R.layout.widget_djini_pulse)

            val screenText = formatMins(synth.todayForegroundMinutes)
            rv.setTextViewText(R.id.widget_screen_val, screenText)
            rv.setTextViewText(R.id.widget_pickups_val, synth.todayPickups.toString())

            if (active != null) {
                val rem = active.remainingMinutes(System.currentTimeMillis())
                rv.setTextViewText(R.id.widget_status_badge, "GUARDING · ${rem}m")
                rv.setTextColor(R.id.widget_status_badge, Color.parseColor("#34D399"))
                rv.setTextViewText(R.id.widget_third_label, "FOCUS")
                rv.setTextViewText(R.id.widget_third_val, "${rem}m")
                rv.setTextViewText(R.id.widget_btn_focus, "End Focus")
                rv.setTextViewText(R.id.widget_subtitle, "Goal: ${active.goal}")
            } else {
                val badge = if (snap.config.enabled) "LIVE · 24/7" else "PAUSED"
                rv.setTextViewText(R.id.widget_status_badge, badge)
                rv.setTextColor(
                    R.id.widget_status_badge,
                    Color.parseColor(if (snap.config.enabled) "#60A5FA" else "#9CA3AF"),
                )
                rv.setTextViewText(R.id.widget_third_label, if (synth.todayDoomscrollEpisodes > 0) "LOOPS" else "FOCUS")
                rv.setTextViewText(
                    R.id.widget_third_val,
                    if (synth.todayDoomscrollEpisodes > 0) synth.todayDoomscrollEpisodes.toString() else "${synth.todayFocusMinutes}m",
                )
                rv.setTextViewText(R.id.widget_btn_focus, "45m Focus")
                val topApp = synth.topAppsToday.firstOrNull()
                val sub = if (topApp != null) {
                    val mins = (topApp.foregroundMs / 60_000L).toInt()
                    "Top today: ${topApp.appLabel.ifBlank { topApp.packageName }} (${mins}m)"
                } else {
                    "Tap to open Djini Pulse dashboard"
                }
                rv.setTextViewText(R.id.widget_subtitle, sub)
            }

            // Open Djini Pulse dashboard when tapping header or metrics
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_BUDDY, true)
            }
            val openPi = PendingIntent.getActivity(
                context,
                9420,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, openPi)

            // Focus toggle button
            val focusIntent = Intent(context, DjiniPulseWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_FOCUS
            }
            val focusPi = PendingIntent.getBroadcast(
                context,
                9421,
                focusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_btn_focus, focusPi)

            // Pulse feel + refresh button
            val pulseIntent = Intent(context, DjiniPulseWidgetProvider::class.java).apply {
                action = ACTION_PULSE_CHECK
            }
            val pulsePi = PendingIntent.getBroadcast(
                context,
                9422,
                pulseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(R.id.widget_btn_pulse, pulsePi)

            return rv
        }

        private fun formatMins(mins: Int): String =
            if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
    }
}
