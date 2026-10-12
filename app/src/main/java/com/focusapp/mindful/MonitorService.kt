package com.focusapp.mindful

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

/**
 * Foreground service that checks every few seconds which app is on screen.
 * When the user has been continuously using a watched app (or the phone in
 * general) for longer than the limit, it brings up [FocusActivity].
 *
 * Checks only run while the screen is on; turning it off resets the session.
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var currentApp: String? = null
    private var lastEventQuery = 0L
    private var sessionStart = 0L

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(tick)
            sessionStart = 0
            if (intent.action == Intent.ACTION_SCREEN_ON) handler.post(tick)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // Look back far enough to find whichever app is already open.
        lastEventQuery = System.currentTimeMillis() - 60 * 60 * 1000
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        handler.removeCallbacks(tick)
        if (isScreenOn()) handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isScreenOn() = getSystemService(PowerManager::class.java).isInteractive

    private fun check() {
        if (!prefs.enabled || !Permissions.hasAll(this)) {
            // A revoked permission turns reminders off so the switch shows the truth.
            prefs.enabled = false
            stopSelf()
            return
        }
        // Time spent looking at the lock screen isn't phone use.
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!isScreenOn() || locked) {
            sessionStart = 0
            return
        }

        updateCurrentApp()
        val app = currentApp
        val watched = prefs.watchedApps
        val counting = when {
            app == packageName -> false            // our own screens never count
            watched.isEmpty() -> true              // all phone usage
            else -> app != null && app in watched
        }

        val now = SystemClock.elapsedRealtime()
        if (!counting) {
            sessionStart = 0
            return
        }
        if (sessionStart == 0L) sessionStart = now

        val usedMs = now - sessionStart
        if (usedMs >= prefs.limitMinutes * 60_000L) {
            showFocusScreen((usedMs / 60_000L).toInt())
            sessionStart = now
        }
    }

    /** Reads usage events since the last check to find the latest foreground app. */
    private fun updateCurrentApp() {
        val usm = getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(lastEventQuery, end)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                isSwitchTarget(event.packageName)
            ) {
                currentApp = event.packageName
            }
        }
        lastEventQuery = end
    }

    private val switchTargetCache = mutableMapOf<String, Boolean>()

    /**
     * Whether opening [pkg] means the user moved to a different app. Screens from
     * packages without a launcher icon (Google sign-in, share sheets, permission
     * dialogs) run on behalf of the app underneath, so they don't end its session.
     */
    private fun isSwitchTarget(pkg: String): Boolean = switchTargetCache.getOrPut(pkg) {
        pkg == packageName ||
            packageManager.getLaunchIntentForPackage(pkg) != null ||
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg), 0
            ).isNotEmpty()
    }

    private fun showFocusScreen(minutes: Int) {
        prefs.recordPause()
        val intent = Intent(this, FocusActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(FocusActivity.EXTRA_MINUTES, minutes)
        startActivity(intent)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "focus_monitor"
        private const val NOTIF_ID = 1
        private const val CHECK_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
