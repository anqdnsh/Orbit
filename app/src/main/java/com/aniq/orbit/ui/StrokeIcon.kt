package com.aniq.orbit.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.aniq.orbit.search.Glyph

/** Line icons drawn on a 24×24 grid. No image files, so nothing to decode at startup. */
class StrokeIcon(private val glyph: Glyph, color: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val r = RectF()
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(s, s)
        when (glyph) {
            Glyph.SEARCH -> {
                c.drawCircle(11f, 11f, 7f, p)
                c.drawLine(16.5f, 16.5f, 20f, 20f, p)
            }
            Glyph.GEAR -> {
                c.drawCircle(12f, 12f, 3f, p)
                c.drawCircle(12f, 12f, 7f, p)
                for (i in 0 until 8) {
                    c.save()
                    c.rotate(i * 45f, 12f, 12f)
                    c.drawLine(12f, 2.5f, 12f, 5f, p)
                    c.restore()
                }
            }
            Glyph.GLOBE -> {
                c.drawCircle(12f, 12f, 9f, p)
                c.drawLine(3f, 12f, 21f, 12f, p)
                r.set(8f, 3f, 16f, 21f)
                c.drawOval(r, p)
            }
            Glyph.CALC -> {
                r.set(5f, 3f, 19f, 21f)
                c.drawRoundRect(r, 2.5f, 2.5f, p)
                c.drawLine(8.5f, 7.5f, 15.5f, 7.5f, p)
                for (y in intArrayOf(12, 16)) for (x in intArrayOf(9, 12, 15)) {
                    c.drawPoint(x.toFloat(), y.toFloat(), p)
                }
            }
            Glyph.POWER -> {
                r.set(4.5f, 5f, 19.5f, 20f)
                c.drawArc(r, -60f, 300f, false, p)
                c.drawLine(12f, 3f, 12f, 11f, p)
            }
            Glyph.BOLT -> {
                path.reset()
                path.moveTo(13f, 2.5f)
                path.lineTo(5.5f, 13.5f)
                path.lineTo(11.5f, 13.5f)
                path.lineTo(10.5f, 21.5f)
                path.lineTo(18.5f, 10.5f)
                path.lineTo(12.5f, 10.5f)
                path.close()
                c.drawPath(path, p)
            }
            Glyph.SPARK -> {
                path.reset()
                path.moveTo(12f, 3f)
                path.quadTo(13f, 11f, 21f, 12f)
                path.quadTo(13f, 13f, 12f, 21f)
                path.quadTo(11f, 13f, 3f, 12f)
                path.quadTo(11f, 11f, 12f, 3f)
                path.close()
                c.drawPath(path, p)
            }
            Glyph.PULSE -> {
                path.reset()
                path.moveTo(2.5f, 12f)
                path.lineTo(7f, 12f)
                path.lineTo(9.5f, 6f)
                path.lineTo(13.5f, 18f)
                path.lineTo(16f, 12f)
                path.lineTo(21.5f, 12f)
                c.drawPath(path, p)
            }
            Glyph.FILE -> {
                path.reset()
                path.moveTo(6f, 3f); path.lineTo(14f, 3f); path.lineTo(19f, 8f); path.lineTo(19f, 21f)
                path.lineTo(6f, 21f); path.close()
                c.drawPath(path, p)
                c.drawLine(14f, 3f, 14f, 8f, p); c.drawLine(14f, 8f, 19f, 8f, p)
            }
            Glyph.TIMER -> {
                c.drawCircle(12f, 13.5f, 7.5f, p)
                c.drawLine(12f, 13.5f, 12f, 9.5f, p)
                c.drawLine(10f, 2.5f, 14f, 2.5f, p)
            }
            Glyph.CROP -> {
                path.reset()
                path.moveTo(6f, 2.5f); path.lineTo(6f, 18f); path.lineTo(21.5f, 18f)
                path.moveTo(2.5f, 6f); path.lineTo(18f, 6f); path.lineTo(18f, 21.5f)
                c.drawPath(path, p)
            }
            Glyph.SUN -> {
                c.drawCircle(12f, 12f, 4f, p)
                for (i in 0 until 8) {
                    c.save(); c.rotate(i * 45f, 12f, 12f); c.drawLine(12f, 2.5f, 12f, 4.5f, p); c.restore()
                }
            }
            Glyph.CASH -> {
                r.set(2.5f, 6f, 21.5f, 18f)
                c.drawRoundRect(r, 2f, 2f, p)
                c.drawCircle(12f, 12f, 2.8f, p)
            }
            Glyph.ORBIT -> {
                // OrbitSearch mark: a black hole (dome on its disk), as in the app icon.
                c.save()
                c.rotate(-7f, 12f, 13f)
                r.set(1.5f, 10f, 22.5f, 16f)
                c.drawOval(r, p)
                r.set(7.2f, 8.2f, 16.8f, 17.8f)
                c.drawArc(r, 180f, 180f, false, p)
                c.drawLine(7.2f, 13f, 16.8f, 13f, p)
                c.restore()
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { p.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
