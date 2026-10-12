package com.focusapp.mindful

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts monitoring after the phone reboots, if the user had it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            Prefs(context).enabled && Permissions.hasAll(context)
        ) {
            MonitorService.start(context)
        }
    }
}
