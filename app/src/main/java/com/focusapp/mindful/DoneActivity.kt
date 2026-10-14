package com.focusapp.mindful

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Invisible screen behind the notification's "I'm done for now" button: counts the
 * stop and goes to the home screen. Android 12+ doesn't let a broadcast receiver
 * open the home screen from a notification tap, so this has to be an activity.
 */
class DoneActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs(this).recordStop()
        FocusOverlay.dismiss(this)
        getSystemService(NotificationManager::class.java).cancel(MonitorService.ALERT_ID)
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        fun intent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, DoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
    }
}
