package com.aniq.orbit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import kotlin.random.Random

/**
 * Frosted, borderless glass: the whole screen behind is blurred by the system, this panel
 * lays a soft tint over it plus a very fine grain (like real frosted glass). No outline,
 * no edge stroke, no divider lines.
 */
class GlassPanel(
    context: Context,
    private val pal: Palette,
    private val opaque: Boolean,
) : LinearLayout(context) {

    private val radius = context.dp(30f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (opaque) pal.fillOpaque else pal.fill
    }
    private val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        alpha = if (pal.dark) 10 else 14
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

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (!opaque) canvas.drawRoundRect(rect, radius, radius, grain)
    }

    companion object {
        /** 96×96 grey noise tile, made once and shared. Tiny, and tiling hides the repeat. */
        private val noise: Bitmap by lazy {
            val n = 96
            val px = IntArray(n * n)
            val r = Random(7)
            for (i in px.indices) {
                val v = r.nextInt(256)
                px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }
    }
}
