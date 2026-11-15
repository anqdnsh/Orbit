package com.aniq.orbit.tools

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.aniq.orbit.ui.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Area screenshot (like Cmd + Shift + 4): Android's accessibility screenshot freezes the screen,
 * you drag a box, and the crop is saved to Pictures/Screenshots/ORBIT and copied.
 * Enter takes the whole screen, Esc cancels. Apps that block screenshots (e.g. banking) show black.
 */
class AreaCapture(private val svc: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val wm = svc.getSystemService(WindowManager::class.java)
    private var overlay: CropView? = null

    fun start() {
        if (overlay != null) return
        // Wait a moment so the ORBIT panel has animated away before the screen is captured.
        main.postDelayed({
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    hb.close()
                    if (bmp == null) toast("Couldn't capture the screen") else showCropper(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    toast("Couldn't capture the screen (try again in a second)")
                }
            })
        }, 260)
    }

    private fun showCropper(bmp: Bitmap) {
        val v = CropView(svc, bmp)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            windowAnimations = 0
            title = "ORBIT area screenshot"
        }
        try {
            wm.addView(v, lp)
            overlay = v
            v.requestFocus()
        } catch (_: Exception) {
            bmp.recycle()
        }
    }

    private fun close() {
        overlay?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun save(full: Bitmap, crop: Rect) {
        close()
        io.execute {
            val part = try { Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height()) } catch (_: Exception) { null }
            val uri = part?.let { write(it) }
            if (part != null && part !== full) part.recycle()
            full.recycle()
            main.post {
                if (uri == null) toast("Couldn't save the screenshot")
                else {
                    svc.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newUri(svc.contentResolver, "Screenshot", uri))
                    toast("Saved to Pictures/Screenshots/ORBIT and copied")
                }
            }
        }
    }

    private fun write(b: Bitmap): Uri? {
        val name = "ORBIT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/ORBIT")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = svc.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        return try {
            cr.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            cr.delete(uri, null, null)
            null
        }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show() }

    /** The frozen screen, dimmed, with your selection shown bright and its size. */
    private inner class CropView(ctx: Context, private val shot: Bitmap) : View(ctx) {
        private val dim = Paint().apply { color = 0x8C000000.toInt() }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.5f); color = Color.WHITE
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ctx.dp(13f) }
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1C1C1E.toInt() }
        private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ctx.dp(15f); textAlign = Paint.Align.CENTER }
        private val src = Rect()
        private val dst = RectF()
        private var x0 = 0f; private var y0 = 0f; private var x1 = 0f; private var y1 = 0f
        private var dragging = false

        init { isFocusable = true; isFocusableInTouchMode = true }

        private fun sel(): RectF = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))

        override fun onDraw(c: Canvas) {
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            c.drawBitmap(shot, null, dst, null)
            c.drawRect(dst, dim)
            if (dragging || abs(x1 - x0) > 2) {
                val r = sel()
                val sx = shot.width / width.toFloat()
                val sy = shot.height / height.toFloat()
                src.set((r.left * sx).toInt(), (r.top * sy).toInt(), (r.right * sx).toInt(), (r.bottom * sy).toInt())
                c.drawBitmap(shot, src, r, null)
                c.drawRect(r, edge)
                val text = "${src.width()} × ${src.height()}"
                val tw = label.measureText(text)
                val ty = if (r.bottom + context.dp(30f) < height) r.bottom + context.dp(8f) else r.top - context.dp(30f)
                c.drawRoundRect(r.left, ty, r.left + tw + context.dp(16f), ty + context.dp(24f), context.dp(8f), context.dp(8f), labelBg)
                c.drawText(text, r.left + context.dp(8f), ty + context.dp(17f), label)
            } else {
                c.drawText("Drag to select an area   ·   Enter: whole screen   ·   Esc: cancel", width / 2f, context.dp(64f), hint)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; x1 = e.x; y1 = e.y; dragging = true }
                MotionEvent.ACTION_MOVE -> { x1 = e.x; y1 = e.y }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    x1 = e.x; y1 = e.y
                    val r = sel()
                    if (r.width() > context.dp(8f) && r.height() > context.dp(8f)) {
                        val sx = shot.width / width.toFloat()
                        val sy = shot.height / height.toFloat()
                        val crop = Rect(
                            (r.left * sx).toInt().coerceIn(0, shot.width - 1), (r.top * sy).toInt().coerceIn(0, shot.height - 1),
                            (r.right * sx).toInt().coerceIn(1, shot.width), (r.bottom * sy).toInt().coerceIn(1, shot.height),
                        )
                        save(shot, crop)
                    }
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            invalidate()
            return true
        }

        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.action != KeyEvent.ACTION_DOWN) return true
            when (e.keyCode) {
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> { close(); shot.recycle() }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> save(shot, Rect(0, 0, shot.width, shot.height))
            }
            return true
        }
    }
}
