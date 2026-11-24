package com.wangzan101.djini.buddy

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads system digital-wellbeing app usage via [UsageStatsManager] when PACKAGE_USAGE_STATS is
 * granted, and resolves user-friendly app labels from [PackageManager].
 */
class AndroidUsageCollector(context: Context) {
    private val app = context.applicationContext
    private val labelCache = HashMap<String, String>()

    fun hasUsageAccess(): Boolean = runCatching {
        val ops = app.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    @Synchronized fun appLabel(packageName: String): String {
        if (packageName.isBlank()) return ""
        labelCache[packageName]?.let { return it }
        val resolved = runCatching {
            val pm = app.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info)?.toString()?.trim().orEmpty()
        }.getOrDefault("").ifBlank { packageName.substringAfterLast('.') }
        labelCache[packageName] = resolved
        return resolved
    }

    /**
     * Collects today's foreground time per package from [UsageStatsManager]. Returns an empty map
     * if Usage Access has not been granted yet (Djini still tracks live Accessibility sessions).
     */
    fun collectTodayUsage(): Map<String, Pair<String, Long>> {
        if (!hasUsageAccess()) return emptyMap()
        val usm = app.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val startOfDayMs = LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val nowMs = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDayMs, nowMs)
        }.getOrNull().orEmpty()
        val totals = LinkedHashMap<String, Long>()
        for (s in stats) {
            val ms = s.totalTimeInForeground
            val pkg = s.packageName ?: continue
            if (ms < 1_000L || pkg == app.packageName || pkg == "android") continue
            totals[pkg] = maxOf(totals[pkg] ?: 0L, ms)
        }
        return totals.mapValues { (pkg, ms) -> appLabel(pkg) to ms }
    }
}
