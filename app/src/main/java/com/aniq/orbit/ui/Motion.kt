package com.aniq.orbit.ui

import android.view.Display
import android.view.WindowManager
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.exp

/** Timings, curves and the high-refresh-rate request shared by both panel hosts. */
object Motion {
    const val ENTER_MS = 280L
    const val EXIT_MS = 150L

    /** A gentle spring: quick start, a ~3% overshoot, then settles. Lands exactly on 1. */
    val springOut = Interpolator { t ->
        val k = 7.5f
        val w = 6.6f
        val end = 1f - exp(-k) * cos(w)
        (1f - exp(-k * t) * cos(w * t)) / end
    }
    val easeOut: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val easeIn: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)

    /**
     * Ask the display for its fastest mode (120 Hz on Tab S9 and newer) while ORBIT is on screen,
     * keeping the current resolution. One UI may still cap this, e.g. in power saving mode or
     * when Motion smoothness is set to Standard.
     */
    fun preferFastest(lp: WindowManager.LayoutParams, display: Display?) {
        display ?: return
        val cur = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        lp.preferredDisplayModeId = best.modeId
        lp.preferredRefreshRate = best.refreshRate
    }
}
