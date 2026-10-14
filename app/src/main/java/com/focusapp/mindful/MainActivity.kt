package com.focusapp.mindful

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var switchEnabled: Switch
    private lateinit var switchNudges: Switch
    private lateinit var txtStatus: TextView
    private lateinit var txtLimit: TextView
    private lateinit var txtPause: TextView

    /** True while we set the switch from code, so the listener ignores it. */
    private var syncingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        switchEnabled = findViewById(R.id.switchEnabled)
        switchNudges = findViewById(R.id.switchNudges)
        txtStatus = findViewById(R.id.txtStatus)
        txtLimit = findViewById(R.id.txtLimit)
        txtPause = findViewById(R.id.txtPause)
        // Keep row ripples inside the rounded glass corners.
        findViewById<View>(R.id.cardPermissions).clipToOutline = true
        findViewById<View>(R.id.cardSettings).clipToOutline = true
        findViewById<View>(R.id.cardNudges).clipToOutline = true

        findViewById<View>(R.id.rowUsage).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<View>(R.id.rowOverlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<View>(R.id.rowNotifications).setOnClickListener { askForNotifications() }
        findViewById<View>(R.id.rowBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<View>(R.id.rowApps).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<View>(R.id.btnPreview).setOnClickListener {
            if (!FocusOverlay.show(this, 0, null, preview = true)) {
                Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_SHORT).show()
            }
        }

        stepper(R.id.btnLimitMinus, R.id.btnLimitPlus, txtLimit, LIMIT_STEPS,
            get = { prefs.limitMinutes }, set = { prefs.limitMinutes = it })
        stepper(R.id.btnPauseMinus, R.id.btnPausePlus, txtPause, PAUSE_STEPS,
            get = { prefs.pauseSeconds }, set = { prefs.pauseSeconds = it })
        stepper(R.id.btnNudgeMinus, R.id.btnNudgePlus, findViewById(R.id.txtNudge), NUDGE_STEPS,
            get = { prefs.nudgeMinutes },
            // Restarting the service picks a new random time with the new interval.
            set = { prefs.nudgeMinutes = it; MonitorService.sync(this) })

        switchNudges.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            if (checked && !notificationsAllowed()) askForNotifications()
            prefs.nudgesEnabled = checked
            MonitorService.sync(this)
        }
        findViewById<View>(R.id.rowTestNudge).setOnClickListener {
            if (!notificationsAllowed()) askForNotifications() else MonitorService.postNudge(this)
        }

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            if (checked && !Permissions.hasUsageAccess(this)) {
                Toast.makeText(this, R.string.need_permissions, Toast.LENGTH_SHORT).show()
                setSwitch(switchEnabled, false)
                return@setOnCheckedChangeListener
            }
            prefs.enabled = checked
            MonitorService.sync(this)
            showStatus()
        }

        if (!notificationsAllowed()) askForNotifications()
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from system settings or the app picker.
        showPermission(R.id.rowUsage, R.id.chipUsage, Permissions.hasUsageAccess(this))
        showPermission(R.id.rowOverlay, R.id.chipOverlay, Permissions.hasOverlay(this))
        showPermission(R.id.rowNotifications, R.id.chipNotifications, notificationsAllowed())
        showPermission(R.id.rowBattery, R.id.chipBattery,
            Permissions.ignoresBatteryOptimization(this))

        val count = prefs.watchedApps.size
        findViewById<TextView>(R.id.txtWatching).text =
            if (count == 0) getString(R.string.watching_all)
            else resources.getQuantityString(R.plurals.watching_n, count, count)

        findViewById<TextView>(R.id.txtPauses).text = prefs.pausesToday.toString()
        findViewById<TextView>(R.id.txtStops).text = prefs.stopsToday.toString()
        txtLimit.text = prefs.limitMinutes.toString()
        txtPause.text = prefs.pauseSeconds.toString()
        findViewById<TextView>(R.id.txtNudge).text = prefs.nudgeMinutes.toString()

        val on = prefs.enabled && Permissions.hasUsageAccess(this)
        if (!on) prefs.enabled = false
        setSwitch(switchEnabled, on)
        setSwitch(switchNudges, prefs.nudgesEnabled)
        showStatus()
        // Make sure the service is running if it should be (e.g. after the app was killed).
        MonitorService.sync(this)
    }

    private fun showStatus() {
        val count = prefs.watchedApps.size
        txtStatus.text = when {
            !Permissions.hasUsageAccess(this) -> getString(R.string.status_need_perms)
            !switchEnabled.isChecked -> getString(R.string.status_off)
            // Without the overlay, the heads-up notification is the reminder.
            !Permissions.hasOverlay(this) -> getString(R.string.status_notif_only)
            count == 0 -> getString(R.string.status_on_all)
            else -> resources.getQuantityString(R.plurals.status_on_n, count, count)
        }
    }

    private fun setSwitch(switch: Switch, checked: Boolean) {
        syncingSwitch = true
        switch.isChecked = checked
        syncingSwitch = false
    }

    private fun showPermission(rowId: Int, chipId: Int, granted: Boolean) {
        findViewById<View>(rowId).isEnabled = !granted
        findViewById<TextView>(chipId).apply {
            setText(if (granted) R.string.chip_granted else R.string.chip_allow)
            setBackgroundResource(if (granted) R.drawable.chip_done else R.drawable.chip_action)
            setTextColor(getColor(if (granted) R.color.accent else R.color.on_accent))
        }
    }

    private fun notificationsAllowed() =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !prefs.askedNotifications
        ) {
            prefs.askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        } else {
            // Already asked once (Android won't show the dialog again), or turned off in settings.
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        showPermission(R.id.rowNotifications, R.id.chipNotifications, notificationsAllowed())
    }

    /** Wires − / + buttons that move between preset values. */
    private fun stepper(
        minusId: Int, plusId: Int, valueView: TextView, steps: IntArray,
        get: () -> Int, set: (Int) -> Unit
    ) {
        findViewById<View>(minusId).setOnClickListener {
            val current = get()
            set(steps.lastOrNull { it < current } ?: steps.first())
            valueView.text = get().toString()
        }
        findViewById<View>(plusId).setOnClickListener {
            val current = get()
            set(steps.firstOrNull { it > current } ?: steps.last())
            valueView.text = get().toString()
        }
    }

    companion object {
        private val LIMIT_STEPS = intArrayOf(1, 2, 3, 5, 10, 15, 20, 25, 30, 45, 60, 90, 120)
        private val PAUSE_STEPS = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60)
        private val NUDGE_STEPS = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240)
    }
}
