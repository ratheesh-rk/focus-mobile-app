package com.focusapp.mindful

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts monitoring after the phone reboots, if the user had reminders or nudges on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && MonitorService.shouldRun(context)) {
            MonitorService.sync(context)
        }
    }
}
