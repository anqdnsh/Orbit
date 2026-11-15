package com.aniq.orbit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.aniq.orbit.tools.FocusTimer
import kotlin.math.max

/**
 * The timer dial. The ring is redrawn every display frame while running (smooth at 120 Hz),
 * and each digit that changes rolls up iOS-style: the old one slides up and fades while the new
 * one rises in on a gentle spring. When paused or hidden, it stops drawing entirely.
 */
class TimerFace(context: Context, private val pal: Palette, private val compact: Boolean) : View(context) {

    private val ringW = context.dp(if (compact) 3f else 11f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringW
        color = if (pal.dark) 0x1FFFFFFF else 0x140B1220
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringW
        strokeCap = Paint.Cap.ROUND
        color = pal.accent
        if (!compact) setShadowLayer(context.dp(10f), 0f, 0f, (pal.accent and 0x00FFFFFF) or 0x66000000)
    }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.text
        textSize = context.dp(if (compact) 17f else 64f)
        typeface = Typeface.create(Typeface.DEFAULT, if (compact) 600 else 300, false)
        fontFeatureSettings = "tnum" // every digit the same width, so nothing jiggles
        textAlign = Paint.Align.LEFT
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.sub
        textSize = context.dp(13f)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        letterSpacing = 0.08f
        textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()

    private var shown = ""
    private var previous = ""
    private val rollStart = LongArray(12)
    private val rollMs = 320L

    var captionText = ""

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (compact) {
            val h = context.dpi(30f)
            setMeasuredDimension(context.dpi(150f), h)
        } else {
            val s = context.dpi(270f)
            setMeasuredDimension(s, s)
        }
    }

    private fun format(ms: Long): String {
        val total = (ms + 999) / 1000 // round up, like iOS: 0:01 shows until it truly ends
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val st = FocusTimer.state
        val text = when (st) {
            FocusTimer.State.DONE -> "0:00"
            FocusTimer.State.IDLE -> "0:00"
            else -> format(FocusTimer.remaining())
        }
        if (text != shown) {
            previous = shown
            for (i in text.indices) {
                val changed = previous.length != text.length || previous.getOrNull(i) != text[i]
                if (changed && i < rollStart.size) rollStart[i] = now
            }
            shown = text
        }
        val left = 1f - FocusTimer.progress()

        if (compact) {
            val r = height / 2f - ringW
            val cx = height / 2f
            val cy = height / 2f
            oval.set(cx - r, cy - r, cx + r, cy + r)
            c.drawOval(oval, track)
            c.drawArc(oval, -90f, 360f * left, false, arc)
            drawDigits(c, height + context.dp(8f), cy, now)
        } else {
            val pad = ringW + context.dp(12f)
            oval.set(pad, pad, width - pad, height - pad)
            c.drawOval(oval, track)
            if (left > 0f) c.drawArc(oval, -90f, 360f * left, false, arc)
            val w = textWidth(shown)
            drawDigits(c, (width - w) / 2f, height / 2f, now)
            if (captionText.isNotEmpty()) c.drawText(captionText, width / 2f, height / 2f + context.dp(52f), caption)
        }

        val rolling = rollStart.any { now - it < rollMs }
        if ((st == FocusTimer.State.RUNNING || rolling) && isShown) postInvalidateOnAnimation()
    }

    private fun charW(ch: Char): Float = if (ch.isDigit()) digits.measureText("0") else digits.measureText(ch.toString())
    private fun textWidth(t: String): Float = t.fold(0f) { a, ch -> a + charW(ch) }

    /** Draws [shown] starting at x, vertically centred on cy, rolling the digits that just changed. */
    private fun drawDigits(c: Canvas, x0: Float, cy: Float, now: Long) {
        val fm = digits.fontMetrics
        val base = cy - (fm.ascent + fm.descent) / 2f
        val h = (fm.descent - fm.ascent) * 0.7f
        var x = x0
        val alignOld = if (previous.length == shown.length) 0 else max(0, previous.length - shown.length)
        for (i in shown.indices) {
            val ch = shown[i]
            val w = charW(ch)
            val t = ((now - rollStart.getOrElse(i) { 0L }).toFloat() / rollMs).coerceIn(0f, 1f)
            if (t >= 1f || previous.isEmpty()) {
                digits.alpha = 255
                c.drawText(ch.toString(), x, base, digits)
            } else {
                val e = Motion.springOut.getInterpolation(t)
                val old = previous.getOrNull(i + alignOld)
                c.save()
                c.clipRect(x - 2, base - h * 1.6f, x + w + 2, base + h * 0.6f)
                if (old != null && old != ch) {
                    digits.alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
                    c.drawText(old.toString(), x, base - h * e, digits)
                }
                digits.alpha = (t * 255).toInt().coerceIn(0, 255)
                c.drawText(ch.toString(), x, base + h * (1f - e), digits)
                c.restore()
                digits.alpha = 255
            }
            x += w
        }
    }

    /** Re-read the timer and restart the frame loop if needed. */
    fun poke() { invalidate() }
}
