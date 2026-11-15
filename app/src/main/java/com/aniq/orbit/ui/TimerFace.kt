package com.aniq.orbit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.aniq.orbit.Prefs
import com.aniq.orbit.TimerFaceKind
import com.aniq.orbit.tools.FocusTimer
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The timer face, in two sizes ([Style.BUBBLE] for the corner, [Style.DIAL] for the card) and
 * three looks ([TimerFaceKind]):
 *  - CLASSIC: a ring that shortens, with rolling mm:ss and "of 25:00".
 *  - HOLE: a black hole whose glowing disk shrinks as time runs out and spins faster near the
 *    end, then collapses into the centre and sends out one red ripple.
 *  - ORBIT: a planet circling the core once a minute, with a fading trail and the time left as a
 *    faint arc; at zero it spirals in and the core flashes.
 * While running (or animating) it redraws every display frame, so it is smooth at 120 Hz; when
 * paused or hidden it stops drawing.
 */
class TimerFace(context: Context, private val pal: Palette, private val look: Style) : View(context) {

    enum class Style { BUBBLE, DIAL }

    private val bubble = look == Style.BUBBLE
    private val sizePx = context.dpi(if (bubble) 52f else 120f)
    private val ringW = context.dp(if (bubble) 4f else 7f)

    var face: TimerFaceKind = Prefs.timerFace(context)
        private set
    private var prevFace: TimerFaceKind? = null
    private var switchAt = 0L
    private val switchMs = 260L

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
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    private var lastFrame = 0L
    private var spin = 0f // black hole disk angle, degrees

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(sizePx, sizePx)

    /** Switch looks with a short cross-fade. */
    fun setFace(f: TimerFaceKind, animate: Boolean = true) {
        if (f == face) return
        prevFace = if (animate) face else null
        switchAt = SystemClock.uptimeMillis()
        face = f
        invalidate()
    }

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
        val dt = if (lastFrame == 0L) 0L else (now - lastFrame).coerceIn(0, 50)
        lastFrame = now
        val st = FocusTimer.state
        val left = FocusTimer.remaining()
        val frac = 1f - FocusTimer.progress() // 1 at the start, 0 at the end
        val doneP = if (st == FocusTimer.State.DONE) ((now - FocusTimer.doneAtUptime) / 1600f).coerceIn(0f, 1f) else 0f

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

        if (st == FocusTimer.State.RUNNING) spin = (spin + dt * (0.03f + 0.42f * (1f - frac).pow(2))) % 360f
        else if (st == FocusTimer.State.DONE && doneP < 1f) spin = (spin + dt * 0.5f) % 360f

        val switchT = ((now - switchAt).toFloat() / switchMs).coerceIn(0f, 1f)
        val old = prevFace
        if (old != null && switchT < 1f) {
            val e = Motion.easeOut.getInterpolation(switchT)
            drawLayer(c, old, 1f - e, 1f - 0.08f * e, now, st, frac, doneP)
            drawLayer(c, face, e, 0.92f + 0.08f * e, now, st, frac, doneP)
        } else {
            prevFace = null
            drawFace(c, face, now, st, frac, doneP)
        }

        val rolling = rollStart.any { now - it < rollMs }
        val paused = st == FocusTimer.State.PAUSED
        val animating = rolling || prevFace != null || (st == FocusTimer.State.DONE && doneP < 1f) || (bubble && paused)
        if ((st == FocusTimer.State.RUNNING || animating) && isShown) postInvalidateOnAnimation()
        else lastFrame = 0L
    }

    private fun drawLayer(c: Canvas, f: TimerFaceKind, alpha: Float, scale: Float, now: Long,
                          st: FocusTimer.State, frac: Float, doneP: Float) {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        if (a == 0) return
        val save = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), a)
        c.scale(scale, scale, width / 2f, height / 2f)
        drawFace(c, f, now, st, frac, doneP)
        c.restoreToCount(save)
    }

    private fun drawFace(c: Canvas, f: TimerFaceKind, now: Long, st: FocusTimer.State, frac: Float, doneP: Float) {
        when (f) {
            TimerFaceKind.CLASSIC -> drawClassic(c, now, st, frac)
            TimerFaceKind.HOLE -> drawHole(c, now, st, frac, doneP)
            TimerFaceKind.ORBIT -> drawOrbit(c, now, st, frac, doneP)
        }
        if (doneP > 0f) drawRipple(c, f, doneP)
    }

    // ---------- classic ----------

    private fun drawClassic(c: Canvas, now: Long, st: FocusTimer.State, frac: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - ringW / 2f - context.dp(if (bubble) 1f else 1.5f)
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawOval(oval, track)
        if (frac > 0f && st != FocusTimer.State.DONE) c.drawArc(oval, -90f, 360f * frac, false, arc)
        digitsCentre(c, now, st, pal.text, if (bubble) 15f else 30f, if (bubble) 0f else -6f)
        if (!bubble) {
            val total = (FocusTimer.totalMs + 999) / 1000
            c.drawText("of " + "%02d:%02d".format(total / 60, total % 60), cx, cy + context.dp(20f), caption)
        }
    }

    // ---------- black hole ----------

    private fun drawHole(c: Canvas, now: Long, st: FocusTimer.State, frac: Float, doneP: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - context.dp(1f)
        val collapse = 1f - (1f - min(1f, doneP * 1.6f)).pow(3)
        val core = r * (if (bubble) 0.5f else 0.56f) * (1f - collapse)
        val endLeft = if (st == FocusTimer.State.DONE) 0f else frac
        val outer = max(0.5f, (core + (r - core) * (0.18f + 0.82f * endLeft)) * (1f - collapse) + core * collapse)

        // Soft red halo, then the disk: white-hot at the shadow's edge, fading to deep red.
        fill.shader = null
        fill.color = pal.accent
        fill.alpha = (56 * (1f - collapse)).toInt()
        c.drawCircle(cx, cy, outer + context.dp(if (bubble) 1.5f else 3f), fill)
        fill.alpha = 255
        val c0 = (core / outer).coerceIn(0f, 0.98f)
        fill.shader = RadialGradient(
            cx, cy, outer,
            intArrayOf(0xFFFFF7E8.toInt(), 0xFFFFD27A.toInt(), 0xFFF08A2C.toInt(), 0xFFB3202A.toInt(), 0x005A0E14),
            floatArrayOf(c0, c0 + (1 - c0) * 0.12f, c0 + (1 - c0) * 0.38f, c0 + (1 - c0) * 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawCircle(cx, cy, outer, fill)
        fill.shader = null

        // Streaks that show the spin.
        if (outer - core > context.dp(3f)) {
            line.strokeWidth = context.dp(if (bubble) 0.9f else 1.4f)
            line.color = 0xFFFFF3DC.toInt()
            line.alpha = (130 * (1f - collapse)).toInt()
            for ((k, at) in floatArrayOf(0.32f, 0.64f).withIndex()) {
                val rr = core + (outer - core) * at
                oval.set(cx - rr, cy - rr, cx + rr, cy + rr)
                val base = spin * (if (k == 0) 1f else 0.8f) + k * 140f
                c.drawArc(oval, base, 34f, false, line)
                c.drawArc(oval, base + 190f, 18f, false, line)
            }
        }

        fill.color = Color.BLACK
        if (core > 0.5f) c.drawCircle(cx, cy, core, fill)

        // Brief white flash as it collapses.
        if (doneP in 0.3f..0.5f) {
            fill.color = 0xFFFFF3DC.toInt()
            fill.alpha = (255 * (1f - kotlin.math.abs(doneP - 0.4f) * 10f)).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, r * 0.25f, fill)
            fill.alpha = 255
        }

        val onCore = collapse < 0.6f
        digitsCentre(c, now, st, if (onCore) Color.WHITE else pal.text, if (bubble) 14f else 22f, 0f)
    }

    // ---------- orbit ----------

    private fun drawOrbit(c: Canvas, now: Long, st: FocusTimer.State, frac: Float, doneP: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val planet = context.dp(if (bubble) 3.2f else 6f)
        val baseR = width / 2f - planet - context.dp(1.5f)
        val spiral = 1f - (1f - min(1f, doneP * 1.4f)).pow(2)
        val orbitR = baseR * (1f - spiral)
        val spent = FocusTimer.totalMs - FocusTimer.remaining()
        val angle = (spent / 60_000f) * 360f + spiral * 540f - 90f // one lap per minute

        // Track and time-left arc.
        line.strokeWidth = context.dp(if (bubble) 1.2f else 2f)
        line.color = if (pal.dark) Color.WHITE else 0xFF0B1220.toInt()
        line.alpha = (36 * (1f - spiral)).toInt()
        oval.set(cx - baseR, cy - baseR, cx + baseR, cy + baseR)
        c.drawOval(oval, line)
        if (st != FocusTimer.State.DONE && frac > 0f) {
            line.color = pal.accent
            line.alpha = 150
            line.strokeWidth = context.dp(if (bubble) 1.6f else 2.5f)
            c.drawArc(oval, -90f, 360f * frac, false, line)
        }

        // Glowing core under the numbers; it flashes when the planet arrives.
        val flash = if (doneP > 0.6f) max(0f, 1f - (doneP - 0.6f) / 0.4f) else 0f
        val glowR = baseR * (0.62f + 0.3f * flash)
        fill.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(withAlpha(pal.accent, (if (pal.dark) 0.42f else 0.26f) + 0.4f * flash), withAlpha(pal.accent, 0f)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(cx, cy, glowR, fill)
        fill.shader = null

        // Planet and its trail.
        if (doneP < 0.7f) {
            val planetColor = if (pal.dark) 0xFFFFF3DC.toInt() else pal.accent
            for (k in 7 downTo 1) {
                val a = Math.toRadians((angle - k * 6f).toDouble())
                fill.color = planetColor
                fill.alpha = (130 * (1f - k / 8f)).toInt()
                c.drawCircle(cx + orbitR * cos(a).toFloat(), cy + orbitR * sin(a).toFloat(), planet * (1f - k * 0.1f), fill)
            }
            val a = Math.toRadians(angle.toDouble())
            fill.color = planetColor
            fill.alpha = 255
            c.drawCircle(cx + orbitR * cos(a).toFloat(), cy + orbitR * sin(a).toFloat(), planet, fill)
        }

        digitsCentre(c, now, st, pal.text, if (bubble) 15f else 24f, 0f)
    }

    // ---------- shared ----------

    private fun drawRipple(c: Canvas, f: TimerFaceKind, doneP: Float) {
        val start = when (f) { TimerFaceKind.HOLE -> 0.35f; TimerFaceKind.ORBIT -> 0.6f; else -> 0f }
        val p = ((doneP - start) / (1f - start)).coerceIn(0f, 1f)
        if (p <= 0f || p >= 1f) return
        val r = width / 2f - context.dp(1.5f)
        line.color = pal.accent
        line.alpha = (255 * (1f - p)).toInt()
        line.strokeWidth = context.dp(if (bubble) 1.5f else 3f)
        c.drawCircle(width / 2f, height / 2f, r * (0.3f + 0.7f * p), line)
    }

    private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    /** The numbers in the middle; paused, they breathe so you notice it's stopped. */
    private fun digitsCentre(c: Canvas, now: Long, st: FocusTimer.State, color: Int, sizeDp: Float, dyDp: Float) {
        digits.color = color
        digits.textSize = context.dp(sizeDp)
        val paused = st == FocusTimer.State.PAUSED
        if (bubble && paused) digits.alpha = (255 * (0.725f + 0.275f * sin(now / 2400.0 * 2 * Math.PI).toFloat())).toInt()
        drawDigits(c, width / 2f - textWidth(shown) / 2f, height / 2f + context.dp(dyDp), now)
        digits.alpha = 255
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
