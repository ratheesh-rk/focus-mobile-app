package com.focusapp.mindful

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var btnUsage: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnBattery: Button
    private lateinit var editLimit: EditText
    private lateinit var editPause: EditText
    private lateinit var txtWatching: TextView
    private lateinit var txtStats: TextView
    private lateinit var switchEnabled: Switch

    /** True while we set the switch from code, so the listener ignores it. */
    private var syncingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        btnUsage = findViewById(R.id.btnUsage)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnBattery = findViewById(R.id.btnBattery)
        editLimit = findViewById(R.id.editLimit)
        editPause = findViewById(R.id.editPause)
        txtWatching = findViewById(R.id.txtWatching)
        txtStats = findViewById(R.id.txtStats)
        switchEnabled = findViewById(R.id.switchEnabled)

        editLimit.setText(prefs.limitMinutes.toString())
        editPause.setText(prefs.pauseSeconds.toString())

        btnUsage.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        btnBattery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            saveNumbers()
            FocusActivity.preview(this)
        }

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            if (checked && !Permissions.hasAll(this)) {
                Toast.makeText(this, R.string.need_permissions, Toast.LENGTH_SHORT).show()
                setSwitch(false)
                return@setOnCheckedChangeListener
            }
            saveNumbers()
            prefs.enabled = checked
            if (checked) MonitorService.start(this) else MonitorService.stop(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from system settings or the app picker.
        showPermissionState(btnUsage, R.string.grant_usage, Permissions.hasUsageAccess(this))
        showPermissionState(btnOverlay, R.string.grant_overlay, Permissions.hasOverlay(this))
        showPermissionState(
            btnBattery, R.string.battery_btn, Permissions.ignoresBatteryOptimization(this)
        )

        val count = prefs.watchedApps.size
        txtWatching.text =
            if (count == 0) getString(R.string.watching_all)
            else resources.getQuantityString(R.plurals.watching_n, count, count)

        txtStats.text = getString(
            R.string.stats_today,
            resources.getQuantityString(R.plurals.stats_pauses, prefs.pausesToday, prefs.pausesToday),
            resources.getQuantityString(R.plurals.stats_stops, prefs.stopsToday, prefs.stopsToday)
        )

        val on = prefs.enabled && Permissions.hasAll(this)
        setSwitch(on)
        // Make sure the service is running if it should be (e.g. after the app was killed).
        if (on) MonitorService.start(this)
    }

    override fun onPause() {
        saveNumbers()
        super.onPause()
    }

    private fun setSwitch(checked: Boolean) {
        syncingSwitch = true
        switchEnabled.isChecked = checked
        syncingSwitch = false
    }

    private fun showPermissionState(button: Button, labelRes: Int, granted: Boolean) {
        button.isEnabled = !granted
        button.text = if (granted) {
            "${getString(labelRes)}  ${getString(R.string.granted)}"
        } else {
            getString(labelRes)
        }
    }

    /** Saves the number fields, then shows the values actually stored (they're clamped). */
    private fun saveNumbers() {
        editLimit.text.toString().toIntOrNull()?.let { prefs.limitMinutes = it }
        editPause.text.toString().toIntOrNull()?.let { prefs.pauseSeconds = it }
        editLimit.setText(prefs.limitMinutes.toString())
        editPause.setText(prefs.pauseSeconds.toString())
    }
}
