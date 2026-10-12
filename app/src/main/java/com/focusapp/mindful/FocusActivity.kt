package com.focusapp.mindful

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.TextView

/** The full-screen pause shown on top of whatever app the user is in. */
class FocusActivity : Activity() {

    private lateinit var btnContinue: Button
    private var timer: CountDownTimer? = null
    private var canContinue = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_focus)

        findViewById<Button>(R.id.btnDone).setOnClickListener { goHome() }
        btnContinue = findViewById(R.id.btnContinue)
        btnContinue.setOnClickListener { if (canContinue) finish() }

        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Already on screen (singleTask): refresh the message and restart the pause.
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        val quotes = resources.getStringArray(R.array.quotes)
        findViewById<TextView>(R.id.txtQuote).text = quotes.random()

        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        findViewById<TextView>(R.id.txtUsed).text =
            if (minutes > 0) resources.getQuantityString(R.plurals.focus_used, minutes, minutes)
            else ""

        startPause(Prefs(this).pauseSeconds)
    }

    private fun startPause(seconds: Int) {
        timer?.cancel()
        canContinue = false
        if (seconds <= 0) {
            unlock()
            return
        }
        btnContinue.isEnabled = false
        timer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                btnContinue.text = getString(R.string.continue_wait, (msLeft / 1000 + 1).toInt())
            }

            override fun onFinish() = unlock()
        }.start()
    }

    private fun unlock() {
        canContinue = true
        btnContinue.isEnabled = true
        btnContinue.setText(R.string.continue_btn)
    }

    private fun goHome() {
        if (!intent.getBooleanExtra(EXTRA_PREVIEW, false)) Prefs(this).recordStop()
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back only works once the pause is over; otherwise it's too easy to skip.
        if (canContinue) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_PREVIEW = "preview"

        /** Opens the focus screen from the app itself, without counting it in stats. */
        fun preview(context: Context) {
            context.startActivity(
                Intent(context, FocusActivity::class.java).putExtra(EXTRA_PREVIEW, true)
            )
        }
    }
}
