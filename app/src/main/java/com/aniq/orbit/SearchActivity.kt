package com.aniq.orbit

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import com.aniq.orbit.ui.Motion
import com.aniq.orbit.ui.OrbitView
import com.aniq.orbit.ui.Palette
import com.aniq.orbit.ui.dpi

/**
 * The same panel in a small floating window, for when ORBIT is opened from its icon,
 * the Quick Settings tile or an Android keyboard shortcut. Looks and moves exactly like
 * the hotkey overlay: blurred screen behind, frosted panel, ORBIT's own 120 Hz animation.
 */
class SearchActivity : Activity(), OrbitView.Host {

    companion object {
        @Volatile
        var current: SearchActivity? = null
            private set
    }

    private lateinit var view: OrbitView
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First launch after install: show the welcome guide instead.
        if (!GuideActivity.done(this)) {
            startActivity(android.content.Intent(this, GuideActivity::class.java))
            finish()
            return
        }
        current = this
        val blur = !Palette.reduced(this) && windowManager.isCrossWindowBlurEnabled

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        view = OrbitView(this, this, opaque = !blur)
        setContentView(view)

        val dm = resources.displayMetrics
        window.setLayout(minOf(dpi(if (Dex.isOn(this) == true) 600f else 720f), (dm.widthPixels * 0.92f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (blur) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (dm.heightPixels * 0.11f).toInt()
            dimAmount = if (Palette.isDark(this@SearchActivity)) 0.30f else if (blur) 0.12f else 0.28f
            if (blur) blurBehindRadius = dpi(48f)
            windowAnimations = 0 // ORBIT animates itself
            Motion.preferFastest(this, display)
        }
        setFinishOnTouchOutside(false) // handled below, so tapping outside animates out
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
    }

    /** Play the exit animation, then close without the system's own window animation. */
    override fun dismiss() {
        if (leaving) return
        leaving = true
        if (!::view.isInitialized) { finish(); return }
        view.animateOut { finishQuietly() }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // Tap outside the panel: animate out instead of vanishing.
        val d = window.decorView
        val outside = event.x < 0 || event.y < 0 || event.x > d.width || event.y > d.height
        if ((event.action == android.view.MotionEvent.ACTION_DOWN && outside) ||
            event.action == android.view.MotionEvent.ACTION_OUTSIDE
        ) {
            dismiss()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish() // the panel never lingers in the background
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
