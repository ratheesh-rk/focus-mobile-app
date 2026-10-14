package com.focusapp.mindful

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.CountDownTimer
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The full-screen pause, drawn as an overlay window on top of whatever app is open.
 *
 * It's a window rather than an Activity because Android 10+ (and many phone makers'
 * battery savers) silently block background services from opening activities, so a
 * pause launched while you watch YouTube never appeared. Overlay windows only need
 * "Display over other apps". Without that, the heads-up notification is the reminder.
 */
object FocusOverlay {

    private var root: View? = null
    private var timer: CountDownTimer? = null
    private var breath: ValueAnimator? = null
    private var audioFocus: AudioFocusRequest? = null
    private var canContinue = false

    val isShowing: Boolean get() = root != null

    /**
     * Shows the pause. [minutes] and [appLabel] describe the session that triggered it.
     * Returns false if the overlay permission is missing.
     */
    fun show(context: Context, minutes: Int, appLabel: String?, preview: Boolean): Boolean {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return false
        dismiss(app)

        val ctx = ContextThemeWrapper(app, R.style.FocusTheme)
        val wm = app.getSystemService(WindowManager::class.java)

        // Swallow Back until the pause is over; otherwise it's too easy to skip.
        val frame = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && canContinue) dismiss(app)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        LayoutInflater.from(ctx).inflate(R.layout.overlay_focus, frame, true)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        // Frosted glass: blur whatever app is underneath where the phone supports it.
        val blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled
        if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = (60 * app.resources.displayMetrics.density).toInt()
        }
        frame.setBackgroundColor(
            app.getColor(if (blur) R.color.scrim_blurred else R.color.scrim_solid)
        )

        val quotes = app.resources.getStringArray(R.array.quotes)
        frame.findViewById<TextView>(R.id.txtQuote).text = quotes.random()
        frame.findViewById<TextView>(R.id.txtUsed).text = when {
            minutes <= 0 -> ""
            appLabel == null ->
                app.resources.getQuantityString(R.plurals.focus_used, minutes, minutes)
            else ->
                app.resources.getQuantityString(R.plurals.focus_used_app, minutes, minutes, appLabel)
        }

        frame.findViewById<Button>(R.id.btnDone).setOnClickListener {
            if (!preview) Prefs(app).recordStop()
            app.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            dismiss(app)
        }
        frame.findViewById<Button>(R.id.btnContinue).setOnClickListener {
            if (canContinue) dismiss(app)
        }

        try {
            wm.addView(frame, params)
        } catch (_: RuntimeException) {
            // Some phones refuse overlays even with the permission; the notification covers it.
            return false
        }
        root = frame
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(400).start()

        pauseMedia(app)
        startBreathing(frame)
        startCountdown(frame, Prefs(app).pauseSeconds)
        return true
    }

    fun dismiss(context: Context) {
        val view = root ?: return
        root = null
        timer?.cancel()
        timer = null
        breath?.cancel()
        breath = null
        canContinue = false
        val app = context.applicationContext
        audioFocus?.let { app.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        audioFocus = null
        app.getSystemService(NotificationManager::class.java).cancel(MonitorService.ALERT_ID)
        try {
            app.getSystemService(WindowManager::class.java).removeView(view)
        } catch (_: IllegalArgumentException) {
            // Already gone.
        }
    }

    /** Asks for audio focus so YouTube, Spotify and others pause during the break. */
    private fun pauseMedia(context: Context) {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        context.getSystemService(AudioManager::class.java).requestAudioFocus(request)
        audioFocus = request
    }

    /** Slow 4-second in / 4-second out pulse to breathe along with. */
    private fun startBreathing(view: View) {
        val ring = view.findViewById<View>(R.id.breathRing)
        val glow = view.findViewById<View>(R.id.breathGlow)
        val label = view.findViewById<TextView>(R.id.txtBreath)
        var inhale = true
        breath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val scale = 0.8f + 0.2f * t
                ring.scaleX = scale
                ring.scaleY = scale
                glow.alpha = 0.35f + 0.65f * t
                glow.scaleX = 0.85f + 0.15f * t
                glow.scaleY = 0.85f + 0.15f * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: Animator) {
                    inhale = !inhale
                    label.setText(if (inhale) R.string.breathe_in else R.string.breathe_out)
                }
            })
            start()
        }
    }

    private fun startCountdown(view: View, seconds: Int) {
        val btnContinue = view.findViewById<Button>(R.id.btnContinue)
        val count = view.findViewById<TextView>(R.id.txtCount)
        canContinue = false

        fun unlock() {
            canContinue = true
            btnContinue.isEnabled = true
            btnContinue.alpha = 1f
            btnContinue.setText(R.string.continue_btn)
            count.visibility = View.GONE
        }

        if (seconds <= 0) {
            unlock()
            return
        }
        btnContinue.isEnabled = false
        btnContinue.alpha = 0.5f
        timer = object : CountDownTimer(seconds * 1000L, 250L) {
            override fun onTick(msLeft: Long) {
                val left = (msLeft / 1000 + 1).toInt()
                count.text = left.toString()
                btnContinue.text = view.context.getString(R.string.continue_wait, left)
            }

            override fun onFinish() = unlock()
        }.start()
    }
}
