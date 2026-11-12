package com.aniq.orbit

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import com.aniq.orbit.ui.OrbitView
import com.aniq.orbit.ui.Palette
import com.aniq.orbit.ui.dp
import com.aniq.orbit.ui.dpi

/**
 * The same panel in a small floating window, for when ORBIT is opened from its icon,
 * the Quick Settings tile or an Android keyboard shortcut. Uses real background blur,
 * clipped to the panel's rounded corners.
 */
class SearchActivity : Activity(), OrbitView.Host {

    companion object {
        @Volatile
        var current: SearchActivity? = null
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        val pal = Palette.current(this)
        val blur = windowManager.isCrossWindowBlurEnabled

        window.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(28f)
            setColor(if (blur) pal.fill else pal.fillOpaque)
        })
        if (blur) window.setBackgroundBlurRadius(dpi(48f))

        setContentView(OrbitView(this, this, drawFill = false, opaque = !blur))

        val dm = resources.displayMetrics
        window.setLayout(minOf(dpi(720f), (dm.widthPixels * 0.92f).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (dm.heightPixels * 0.11f).toInt()
        }
        setFinishOnTouchOutside(true)
    }

    override fun dismiss() = finish()

    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish() // the panel never lingers in the background
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
