package com.focusapp.mindful

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.time.LocalDate

/** Simple wrapper around SharedPreferences for the user's settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("focus_prefs", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Minutes of continuous use before the focus screen appears. */
    var limitMinutes: Int
        get() = sp.getInt("limit_minutes", 15)
        set(v) = sp.edit().putInt("limit_minutes", v.coerceIn(1, 999)).apply()

    /** Seconds the user must wait before "Continue" unlocks. */
    var pauseSeconds: Int
        get() = sp.getInt("pause_seconds", 10)
        set(v) = sp.edit().putInt("pause_seconds", v.coerceIn(0, 999)).apply()

    /** Apps to watch. Empty means "count all phone usage". */
    var watchedApps: Set<String>
        get() = sp.getStringSet("watched_apps", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("watched_apps", v).apply()

    /** How many times the focus screen was shown today. */
    val pausesToday: Int get() = todayCount("pauses")

    /** How many times today the user chose "I'm done for now". */
    val stopsToday: Int get() = todayCount("stops")

    fun recordPause() = increment("pauses")

    fun recordStop() = increment("stops")

    private fun todayCount(key: String): Int =
        if (sp.getLong("stats_day", -1) == today()) sp.getInt(key, 0) else 0

    private fun increment(key: String) {
        val day = today()
        val edit = sp.edit()
        if (sp.getLong("stats_day", -1) != day) {
            edit.putLong("stats_day", day).putInt("pauses", 0).putInt("stops", 0)
        }
        edit.putInt(key, todayCount(key) + 1).apply()
    }

    private fun today() = LocalDate.now().toEpochDay()
}

object Permissions {
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasAll(context: Context) = hasUsageAccess(context) && hasOverlay(context)

    /** Optional, but keeps aggressive battery savers from killing the monitor. */
    fun ignoresBatteryOptimization(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
}
