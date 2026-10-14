package com.focusapp.mindful

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/** Lets the user tick which apps should trigger the focus screen. */
class AppPickerActivity : Activity() {

    private class AppItem(val packageName: String, val label: String, val icon: Drawable)

    private lateinit var prefs: Prefs
    private lateinit var listView: ListView
    private val adapter = AppAdapter()
    private val selected = mutableSetOf<String>()

    private var allApps = emptyList<AppItem>()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        prefs = Prefs(this)
        selected += prefs.watchedApps

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        listView = findViewById(R.id.listApps)
        listView.clipToOutline = true
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val pkg = adapter.getItem(position).packageName
            if (!selected.remove(pkg)) selected += pkg
            adapter.notifyDataSetChanged()
        }

        findViewById<EditText>(R.id.editSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s.toString().trim()
                adapter.refresh()
            }
        })

        // Loading labels and icons for every app is slow, so do it off the main thread.
        val picked = selected.toSet()
        Thread {
            val apps = loadApps(picked)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allApps = apps
                findViewById<View>(R.id.txtLoading).visibility = View.GONE
                adapter.refresh()
            }
        }.start()
    }

    override fun onPause() {
        prefs.watchedApps = selected.toSet()
        super.onPause()
    }

    private fun loadApps(picked: Set<String>): List<AppItem> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm))
            }
        // Already-picked apps first, then alphabetical.
        return apps.sortedWith(
            compareBy<AppItem> { it.packageName !in picked }.thenBy { it.label.lowercase() }
        )
    }

    private inner class AppAdapter : BaseAdapter() {
        private var shown = emptyList<AppItem>()

        fun refresh() {
            shown = if (query.isEmpty()) allApps
            else allApps.filter { it.label.contains(query, ignoreCase = true) }
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val app = shown[position]
            view.findViewById<ImageView>(R.id.imgIcon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.txtLabel).text = app.label
            view.findViewById<CheckBox>(R.id.check).isChecked = app.packageName in selected
            return view
        }
    }
}
