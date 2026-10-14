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
import kotlin.random.Random

/**
 * Foreground service that does two things while the screen is on and unlocked:
 *
 * - Focus reminders: checks every few seconds which app is on screen. After the
 *   user has been continuously using a watched app (or the phone in general) for
 *   longer than the limit, it shows [FocusOverlay] plus a heads-up notification.
 *   If the overlay can't be shown, the notification alone does the job.
 * - Random nudges: a pop-up notification at unpredictable moments.
 *
 * Turning the screen off resets the usage session.
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var currentApp: String? = null
    private var lastEventQuery = 0L
    private var sessionStart = 0L
    private var nextNudgeAt = 0L

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
            updateStatus(idleStatus())
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                // Don't greet the user with an overdue nudge the instant they pick up the phone.
                val now = SystemClock.elapsedRealtime()
                if (nextNudgeAt != 0L && nextNudgeAt < now + 60_000L) {
                    nextNudgeAt = now + Random.nextLong(60_000L, 5 * 60_000L)
                }
                handler.post(tick)
            }
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
        // Settings may have changed (e.g. the nudge interval), so plan the next nudge afresh.
        nextNudgeAt = 0
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

    private fun tracking() = prefs.enabled && Permissions.hasUsageAccess(this)

    private fun idleStatus() =
        getString(if (tracking()) R.string.notif_text else R.string.notif_nudges_only)

    private fun check() {
        if (prefs.enabled && !Permissions.hasUsageAccess(this)) {
            // A revoked permission turns reminders off so the switch shows the truth.
            prefs.enabled = false
        }
        if (!shouldRun(this)) {
            stopSelf()
            return
        }
        // Time spent looking at the lock screen isn't phone use.
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!isScreenOn() || locked) {
            sessionStart = 0
            return
        }

        val now = SystemClock.elapsedRealtime()
        checkNudge(now)
        if (tracking()) checkUsage(now) else updateStatus(idleStatus())
    }

    private fun checkUsage(now: Long) {
        updateCurrentApp()
        val app = currentApp
        val watched = prefs.watchedApps
        val counting = when {
            app == packageName -> false            // our own screens never count
            watched.isEmpty() -> true              // all phone usage
            else -> app != null && app in watched
        }

        // The break itself doesn't count; a fresh session starts once it's dismissed.
        if (!counting || FocusOverlay.isShowing) {
            sessionStart = 0
            updateStatus(idleStatus())
            return
        }
        if (sessionStart == 0L) sessionStart = now

        val usedMs = now - sessionStart
        val label = if (watched.isEmpty()) getString(R.string.notif_phone) else appLabel(app!!)
        updateStatus(
            getString(R.string.notif_progress, label, (usedMs / 60_000L).toInt(), prefs.limitMinutes)
        )
        if (usedMs >= prefs.limitMinutes * 60_000L) {
            showFocusScreen((usedMs / 60_000L).toInt(), if (watched.isEmpty()) null else label)
            sessionStart = 0
        }
    }

    /** Fires a nudge when its random time comes, then picks the next one. */
    private fun checkNudge(now: Long) {
        if (!prefs.nudgesEnabled) {
            nextNudgeAt = 0
            return
        }
        if (nextNudgeAt == 0L) {
            nextNudgeAt = now + randomGap()
        } else if (now >= nextNudgeAt && !FocusOverlay.isShowing) {
            postNudge(this)
            nextNudgeAt = now + randomGap()
        }
    }

    /** Somewhere between half and one and a half times the chosen interval. */
    private fun randomGap(): Long {
        val avg = prefs.nudgeMinutes * 60_000L
        return Random.nextLong(avg / 2, avg * 3 / 2 + 1)
    }

    private val labelCache = mutableMapOf<String, String>()

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
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

    /**
     * Pops a heads-up notification at the top of the screen and draws the pause over
     * the current app. When the overlay can't be shown (permission off, or the phone
     * blocks it), the notification stays as the reminder, with the quote and buttons.
     */
    private fun showFocusScreen(minutes: Int, appLabel: String?) {
        prefs.recordPause()
        val quote = resources.getStringArray(R.array.quotes).random()
        val text = resources.getQuantityString(
            R.plurals.alert_text, minutes, minutes, appLabel ?: getString(R.string.your_phone)
        )
        val alert = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.accent))
            .setContentTitle(getString(R.string.focus_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText("$text\n\n$quote"))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(this))
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.done_btn), DoneActivity.intent(this)
                ).build()
            )
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_ID, alert)

        FocusOverlay.show(this, minutes, appLabel, preview = false)
    }

    private var statusText: String? = null

    private fun buildStatus(text: String) = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(getColor(R.color.accent))
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setContentIntent(openAppIntent(this))
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .build()

    /** Shows live progress, e.g. "YouTube · 8 of 15 min", in the ongoing notification. */
    private fun updateStatus(text: String) {
        if (text == statusText) return
        statusText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildStatus(text))
    }

    private fun startInForeground() {
        createChannels(this)
        val text = statusText ?: idleStatus()
        statusText = text
        val notification = buildStatus(text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val OLD_CHANNEL_ID = "focus_monitor"
        private const val CHANNEL_ID = "focus_status"
        private const val ALERT_CHANNEL_ID = "focus_alerts"
        private const val NUDGE_CHANNEL_ID = "focus_nudges"
        private const val NOTIF_ID = 1
        const val ALERT_ID = 2
        private const val NUDGE_ID = 3
        private const val CHECK_INTERVAL_MS = 5_000L

        /** Whether either feature is on, so the service has something to do. */
        fun shouldRun(context: Context): Boolean {
            val prefs = Prefs(context)
            return (prefs.enabled && Permissions.hasUsageAccess(context)) || prefs.nudgesEnabled
        }

        /** Starts or stops the service to match the current settings. */
        fun sync(context: Context) {
            if (shouldRun(context)) {
                context.startForegroundService(Intent(context, MonitorService::class.java))
            } else {
                context.stopService(Intent(context, MonitorService::class.java))
            }
        }

        private fun openAppIntent(context: Context) = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel(OLD_CHANNEL_ID)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, context.getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
            // High importance is what makes these drop down over the top of the screen.
            nm.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID, context.getString(R.string.alert_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    NUDGE_CHANNEL_ID, context.getString(R.string.nudge_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { setShowBadge(false) }
            )
        }

        /** A gentle pop-up with a random mindful prompt. */
        fun postNudge(context: Context) {
            createChannels(context)
            val text = context.resources.getStringArray(R.array.nudges).random()
            val nudge = Notification.Builder(context, NUDGE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.accent))
                .setContentTitle(context.getString(R.string.nudge_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                // Old nudges shouldn't pile up in the shade.
                .setTimeoutAfter(10 * 60_000L)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NUDGE_ID, nudge)
        }
    }
}
