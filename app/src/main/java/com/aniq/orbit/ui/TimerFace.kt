package com.aniq.orbit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.aniq.orbit.tools.FocusTimer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/**
 * The timer ring, in two sizes:
 *  - [Style.BUBBLE]: the small corner bubble, a thin ring with the minutes left in the middle
 *    (the number gently pulses while paused).
 *  - [Style.DIAL]: the ring inside the expanded card, with rolling mm:ss digits and "of 25:00".
 * While running the ring is redrawn every display frame (smooth at 120 Hz); when paused or
 * hidden it stops drawing.
 */
class TimerFace(context: Context, private val pal: Palette, private val look: Style) : View(context) {

    enum class Style { BUBBLE, DIAL }

    private val bubble = look == Style.BUBBLE
    private val sizePx = context.dpi(if (bubble) 52f else 120f)
    private val ringW = context.dp(if (bubble) 4f else 7f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringW
        color = if (pal.dark) 0x1FFFFFFF else 0x1A0B1220
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringW
        strokeCap = Paint.Cap.ROUND
        color = pal.accent
    }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.text
        textSize = context.dp(if (bubble) 15f else 30f)
        typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        fontFeatureSettings = "tnum" // every digit the same width, so nothing jiggles
        letterSpacing = if (bubble) -0.02f else -0.03f
        textAlign = Paint.Align.LEFT
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.sub
        textSize = context.dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()

    private var shown = ""
    private var previous = ""
    private val rollStart = LongArray(12)
    private val rollMs = 320L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(sizePx, sizePx)

    private fun clock(ms: Long): String {
        val total = (ms + 999) / 1000 // round up, like iOS: 0:01 shows until it truly ends
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun minutesLeft(ms: Long): String =
        if (ms <= 0) "0" else max(1, ceil(ms / 60_000.0).toInt()).toString()

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val st = FocusTimer.state
        val left = FocusTimer.remaining()
        val text = when {
            st == FocusTimer.State.DONE || st == FocusTimer.State.IDLE -> if (bubble) "0" else "00:00"
            bubble -> minutesLeft(left)
            else -> clock(left)
        }
        if (text != shown) {
            previous = shown
            for (i in text.indices) {
                val changed = previous.length != text.length || previous.getOrNull(i) != text[i]
                if (changed && i < rollStart.size) rollStart[i] = now
            }
            shown = text
        }

        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - ringW / 2f - context.dp(if (bubble) 1f else 1.5f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawOval(oval, track)
        val frac = 1f - FocusTimer.progress()
        if (frac > 0f) c.drawArc(oval, -90f, 360f * frac, false, arc)

        val paused = st == FocusTimer.State.PAUSED
        if (bubble) {
            // Paused: the number breathes between full and 45% so you notice it's stopped.
            digits.alpha = if (paused) (255 * (0.725f + 0.275f * sin(now / 2400.0 * 2 * Math.PI).toFloat())).toInt() else 255
            drawDigits(c, cx - textWidth(shown) / 2f, cy, now)
            digits.alpha = 255
        } else {
            drawDigits(c, cx - textWidth(shown) / 2f, cy - context.dp(6f), now)
            val total = (FocusTimer.totalMs + 999) / 1000
            c.drawText("of " + "%02d:%02d".format(total / 60, total % 60), cx, cy + context.dp(20f), caption)
        }

        val rolling = rollStart.any { now - it < rollMs }
        if ((st == FocusTimer.State.RUNNING || rolling || (bubble && paused)) && isShown) postInvalidateOnAnimation()
    }

    private fun charW(ch: Char): Float = if (ch.isDigit()) digits.measureText("0") else digits.measureText(ch.toString())
    private fun textWidth(t: String): Float = t.fold(0f) { a, ch -> a + charW(ch) }

    /** Draws [shown] starting at x, vertically centred on cy, rolling the digits that just changed. */
    private fun drawDigits(c: Canvas, x0: Float, cy: Float, now: Long) {
        val fm = digits.fontMetrics
        val base = cy - (fm.ascent + fm.descent) / 2f
        val h = (fm.descent - fm.ascent) * 0.7f
        val full = digits.alpha
        var x = x0
        val alignOld = if (previous.length == shown.length) 0 else max(0, previous.length - shown.length)
        for (i in shown.indices) {
            val ch = shown[i]
            val w = charW(ch)
            val t = ((now - rollStart.getOrElse(i) { 0L }).toFloat() / rollMs).coerceIn(0f, 1f)
            if (t >= 1f || previous.isEmpty()) {
                c.drawText(ch.toString(), x, base, digits)
            } else {
                val e = Motion.springOut.getInterpolation(t)
                val old = previous.getOrNull(i + alignOld)
                c.save()
                c.clipRect(x - 2, base - h * 1.6f, x + w + 2, base + h * 0.6f)
                if (old != null && old != ch) {
                    digits.alpha = ((1f - t) * full).toInt().coerceIn(0, 255)
                    c.drawText(old.toString(), x, base - h * e, digits)
                }
                digits.alpha = (t * full).toInt().coerceIn(0, 255)
                c.drawText(ch.toString(), x, base + h * (1f - e), digits)
                c.restore()
                digits.alpha = full
            }
            x += w
        }
    }

    /** Re-read the timer and restart the frame loop if needed. */
    fun poke() { invalidate() }
}
