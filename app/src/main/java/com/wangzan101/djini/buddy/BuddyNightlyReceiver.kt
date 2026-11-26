package com.wangzan101.djini.buddy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wangzan101.djini.DjiniApp

/**
 * Alarm receiver for Djini Pulse's automated nightly digital-habit synthesis & coaching brief.
 */
class BuddyNightlyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BuddyNightlyScheduler.ACTION_NIGHTLY_BRIEF) return
        val app = context.applicationContext as? DjiniApp ?: return
        val pending = goAsync()
        app.buddy.runNightlyBriefAsync(fromAlarm = true) {
            pending.finish()
        }
    }
}
