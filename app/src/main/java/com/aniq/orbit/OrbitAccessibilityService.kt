package com.aniq.orbit

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.aniq.orbit.ui.Motion
import com.aniq.orbit.ui.OrbitView
import com.aniq.orbit.ui.dpi

/**
 * Watches key presses for the ORBIT hotkey only, and shows the panel as an overlay window.
 * Because this service stays running, the panel appears instantly: no app start, no activity.
 * Every other key is passed straight through untouched.
 */
class OrbitAccessibilityService : AccessibilityService(), OrbitView.Host {

    companion object {
        @Volatile
        var instance: OrbitAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: OrbitView? = null
    private var hotkey = Hotkey.ALT_SPACE
    private var swallowSpaceUp = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == "hotkey") hotkey = Hotkey.fromId(p.getString("hotkey", null))
    }

    override fun onServiceConnected() {
        instance = this
        hotkey = Prefs.hotkey(this)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (hotkey.matches(event)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                swallowSpaceUp = true
                if (event.repeatCount == 0) main.post { toggle() }
            } else {
                swallowSpaceUp = false
            }
            return true
        }
        // Modifier released before Space: still eat the matching Space-up so apps don't see half a key press.
        if (swallowSpaceUp && event.keyCode == KeyEvent.KEYCODE_SPACE && event.action == KeyEvent.ACTION_UP) {
            swallowSpaceUp = false
            return true
        }
        return false
    }

    fun toggle() {
        val activity = SearchActivity.current
        when {
            overlay != null -> dismiss()
            activity != null -> activity.finish()
            else -> show()
        }
    }

    private fun show() {
        val wm = getSystemService(WindowManager::class.java)
        val blur = wm.isCrossWindowBlurEnabled
        val dm = resources.displayMetrics
        val width = minOf(dpi(if (Dex.isOn(this) == true) 600f else 720f), (dm.widthPixels * 0.92f).toInt())

        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight)
        val view = OrbitView(themed, this, opaque = !blur)
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        if (blur) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND

        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (dm.heightPixels * 0.11f).toInt()
            // Dark mode dims more so wallpaper colour doesn't tint the charcoal glass.
            dimAmount = if (com.aniq.orbit.ui.Palette.isDark(this@OrbitAccessibilityService)) 0.30f else if (blur) 0.12f else 0.28f
            if (blur) blurBehindRadius = dpi(48f) // heavier blur = more frosted
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "ORBIT"
            windowAnimations = 0 // ORBIT animates itself (Motion.kt)
        }
        Motion.preferFastest(
            lp, getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        )
        try {
            wm.addView(view, lp)
            overlay = view
        } catch (_: Exception) {
            // Overlay refused (rare): fall back to the activity window.
            startActivity(android.content.Intent(this, SearchActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun dismiss() = close(animated = true)

    private fun close(animated: Boolean) {
        val v = overlay ?: return
        overlay = null
        val remove = {
            try { getSystemService(WindowManager::class.java).removeViewImmediate(v) } catch (_: Exception) {}
        }
        if (animated && v.isAttachedToWindow) v.animateOut { remove() } else remove()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        close(animated = false) // rotation, dark-mode switch or entering DeX: reopen fresh next time
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        close(animated = false)
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        instance = null
        super.onDestroy()
    }
}
