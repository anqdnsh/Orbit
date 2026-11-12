package com.aniq.orbit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/**
 * The liquid-glass slab: translucent tint (the system blur sits behind it) plus a thin
 * edge that is bright at the top and fades out, which reads as light catching curved glass.
 */
class GlassPanel(
    context: Context,
    private val pal: Palette,
    /** False when the window background already carries the tint (activity host). */
    private val drawFill: Boolean,
    private val opaque: Boolean,
) : LinearLayout(context) {

    private val radius = context.dp(28f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (opaque) pal.fillOpaque else pal.fill
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.2f)
    }
    private val rect = RectF()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        edge.shader = LinearGradient(
            0f, 0f, 0f, h * 0.6f, pal.strokeTop, pal.strokeBottom, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val inset = edge.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)
        if (drawFill) canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius - inset, radius - inset, edge)
    }
}
