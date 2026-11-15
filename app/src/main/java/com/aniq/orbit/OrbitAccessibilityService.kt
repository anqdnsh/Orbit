package com.aniq.orbit

import android.accessibilityservice.AccessibilityService
import android.content.ComponentCallbacks2
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.aniq.orbit.search.IconLoader
import com.aniq.orbit.tools.AreaCapture
import com.aniq.orbit.tools.KeepAwake
import com.aniq.orbit.ui.Motion
import com.aniq.orbit.ui.OrbitView
import com.aniq.orbit.ui.Palette
import com.aniq.orbit.ui.TimerOverlay
import com.aniq.orbit.ui.dpi

/**
 * Watches key presses for the ORBIT hotkeys only (Alt + Space and Alt + Shift + S), and shows
 * the panel as an overlay window. Every other key is passed straight through untouched.
 *
 * Speed: this service keeps ORBIT's process warm, and a panel is built ahead of time and reused,
 * so opening is just "add a ready window". When Android asks for memory back, the spare panel
 * and icon cache are dropped. Idle cost is zero: no timers, no polling, no screen reading.
 */
class OrbitAccessibilityService : AccessibilityService(), OrbitView.Host {

    companion object {
        @Volatile
        var instance: OrbitAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: OrbitView? = null
    private var spare: OrbitView? = null
    private var spareKey = ""
    private var hotkey = Hotkey.ALT_SPACE
    private var swallowUp = -1
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            "hotkey" -> hotkey = Hotkey.fromId(p.getString("hotkey", null))
            "theme", "reduce_effects" -> dropSpare()
        }
    }

    lateinit var area: AreaCapture
        private set
    lateinit var timer: TimerOverlay
        private set

    override fun onServiceConnected() {
        instance = this
        area = AreaCapture(this)
        timer = TimerOverlay(this)
        hotkey = Prefs.hotkey(this)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefListener)
        // Build a panel while the tablet is idle, so the first Alt + Space is as fast as the rest.
        Looper.myQueue().addIdleHandler { prepareSpare(); false }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (hotkey.matches(event)) {
            if (down) {
                swallowUp = event.keyCode
                if (event.repeatCount == 0) {
                    val t0 = SystemClock.uptimeMillis()
                    main.post { toggle(t0) }
                }
            } else swallowUp = -1
            return true
        }
        // Alt + Shift + S: area screenshot from anywhere.
        if (event.keyCode == KeyEvent.KEYCODE_S && event.isAltPressed && event.isShiftPressed && !event.isCtrlPressed) {
            if (down) {
                swallowUp = event.keyCode
                if (event.repeatCount == 0) main.post { close(animated = false); area.start() }
            } else swallowUp = -1
            return true
        }
        // Modifier released first: still eat the matching key-up so apps don't see half a key press.
        if (!down && swallowUp == event.keyCode) {
            swallowUp = -1
            return true
        }
        return false
    }

    fun toggle(t0: Long = SystemClock.uptimeMillis()) {
        GuideActivity.onHotkey?.invoke()
        val activity = SearchActivity.current
        when {
            overlay != null -> dismiss()
            activity != null -> activity.dismiss()
            else -> show(t0)
        }
    }

    // ---------- the panel ----------

    private fun blurOn(): Boolean =
        !Palette.reduced(this) && getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled

    private fun panelWidth(): Int {
        val dm = resources.displayMetrics
        return minOf(dpi(if (Dex.isOn(this) == true) 600f else 720f), (dm.widthPixels * 0.92f).toInt())
    }

    private fun keyNow(): String = "${Palette.isDark(this)}|${blurOn()}|${panelWidth()}"

    private fun prepareSpare() {
        if (spare != null || overlay != null) return
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight)
        spare = OrbitView(themed, this, opaque = !blurOn())
        spareKey = keyNow()
    }

    private fun dropSpare() {
        spare = null
        spareKey = ""
    }

    private fun show(t0: Long) {
        val wm = getSystemService(WindowManager::class.java)
        val blur = blurOn()
        val dm = resources.displayMetrics

        val key = keyNow()
        val ready = spare?.takeIf { spareKey == key && !it.isAttachedToWindow }
        val view = if (ready != null) {
            spare = null
            ready.reset()
            ready
        } else {
            OrbitView(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight), this, opaque = !blur)
        }
        spareKey = key

        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        if (blur) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND

        val lp = WindowManager.LayoutParams(
            panelWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (dm.heightPixels * 0.11f).toInt()
            // Dark mode dims more so wallpaper colour doesn't tint the charcoal glass.
            dimAmount = if (view.pal.dark) 0.30f else if (blur) 0.12f else 0.28f
            if (blur) blurBehindRadius = dpi(48f) // heavier blur = more frosted
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "OrbitSearch"
            windowAnimations = 0 // ORBIT animates itself (Motion.kt)
        }
        Motion.preferFastest(lp, getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))

        // Measure hotkey → first frame on screen.
        val obs = view.viewTreeObserver
        var measured = false
        val firstDraw = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (measured) return
                measured = true
                Stats.recordOpen(SystemClock.uptimeMillis() - t0)
                val self = this
                view.post { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(self) }
            }
        }
        if (obs.isAlive) obs.addOnDrawListener(firstDraw)

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
            // Keep it for next time (if nothing about the look has changed meanwhile).
            if (spare == null && spareKey == keyNow()) spare = v
        }
        if (animated && v.isAttachedToWindow) v.animateOut { remove() } else remove()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        close(animated = false) // rotation, dark-mode switch or entering DeX: reopen fresh next time
        dropSpare()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android is short on memory: give back everything we can rebuild later.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && overlay == null) {
            dropSpare()
            IconLoader.trim()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        close(animated = false)
        dropSpare()
        KeepAwake.stop()
        if (::timer.isInitialized) timer.hideAll()
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        instance = null
        super.onDestroy()
    }
}

/** Simple performance numbers, shown in ORBIT settings › Performance. */
object Stats {
    var lastOpenMs = -1L
        private set
    var avgOpenMs = -1.0
        private set
    var opens = 0
        private set

    fun recordOpen(ms: Long) {
        lastOpenMs = ms
        opens++
        avgOpenMs = if (avgOpenMs < 0) ms.toDouble() else avgOpenMs * 0.8 + ms * 0.2
    }
}
