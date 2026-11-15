package com.aniq.orbit.tools

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.text.DateFormat
import java.util.Date

/**
 * "Caffeinate": a 1×1 invisible overlay with FLAG_KEEP_SCREEN_ON. That's all it takes; no wake
 * lock, no background service. Removing the window lets the screen sleep normally again.
 */
object KeepAwake {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var wm: WindowManager? = null
    /** Wall-clock end time in ms, Long.MAX_VALUE for "until I turn it off", 0 when off. */
    var until = 0L
        private set
    private val stopper = Runnable { stop() }

    val isOn get() = view != null

    /** [ctx] must be the accessibility service (it owns overlay windows). */
    fun start(ctx: Context, minutes: Int?): Boolean {
        stop()
        val w = ctx.getSystemService(WindowManager::class.java)
        val v = View(ctx)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "ORBIT keep awake"
        }
        return try {
            w.addView(v, lp)
            view = v
            wm = w
            if (minutes != null) {
                until = System.currentTimeMillis() + minutes * 60_000L
                main.postDelayed(stopper, minutes * 60_000L)
            } else {
                until = Long.MAX_VALUE
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        main.removeCallbacks(stopper)
        view?.let { v -> try { wm?.removeViewImmediate(v) } catch (_: Exception) {} }
        view = null
        until = 0L
    }

    fun label(): String = when {
        !isOn -> "Off"
        until == Long.MAX_VALUE -> "On until you turn it off"
        else -> "On until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))
    }
}
