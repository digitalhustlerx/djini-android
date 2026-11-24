package com.wangzan101.djini.buddy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.wangzan101.djini.core.buddy.BuddyConfig
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedules or cancels the recurring nightly Djini Pulse habit-synthesis alarm using Android's
 * [AlarmManager]. Re-armed automatically on boot, timezone change, config change, and after firing.
 */
object BuddyNightlyScheduler {
    private const val TAG = "DjiniNightly"
    private const val REQUEST_CODE = 9410
    const val ACTION_NIGHTLY_BRIEF = "com.wangzan101.djini.ACTION_BUDDY_NIGHTLY_BRIEF"

    fun sync(context: Context, config: BuddyConfig) {
        if (!config.enabled || !config.nightlyBriefEnabled) {
            cancel(context)
            return
        }
        scheduleNext(context, config.nightlyBriefHour.coerceIn(0, 23), config.nightlyBriefMinute.coerceIn(0, 59))
    }

    fun scheduleNext(context: Context, hour: Int, minute: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAtMs = nextTriggerEpochMs(hour, minute)
        val pi = pendingIntent(context)
        runCatching {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        }.onFailure { Log.w(TAG, "Failed to schedule nightly brief alarm", it) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingIntent(context)) }
    }

    fun nextTriggerEpochMs(hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.now(zone)
        var target = now.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59)).withSecond(0).withNano(0)
        if (!target.isAfter(now.plusSeconds(30))) {
            target = target.plusDays(1)
        }
        return target.toInstant().toEpochMilli()
    }

    fun formatTime(hour: Int, minute: Int): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        return "%02d:%02d".format(h, m)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BuddyNightlyReceiver::class.java).apply { action = ACTION_NIGHTLY_BRIEF }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
