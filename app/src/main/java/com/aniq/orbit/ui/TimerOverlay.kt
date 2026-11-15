package com.aniq.orbit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.aniq.orbit.Prefs
import com.aniq.orbit.TimerFaceKind
import com.aniq.orbit.tools.FocusTimer
import kotlin.math.abs

/**
 * The focus timer's floating UI, in the bottom-right corner of the screen:
 *  - a small round bubble (thin ring + minutes left) that stays out of your way,
 *  - which grows in place into a compact card (ring, mm:ss, Pause / +5 min / Stop).
 * Esc, the chevron or tapping outside shrinks the card back into the bubble; tapping the bubble
 * opens the card. Nothing dims or blurs the screen. Only GPU-cheap properties are animated.
 * The face (Classic, Black hole, Orbit) is picked on the card itself: swipe the face, tap the
 * dots under it, or press ← →. The bubble uses the same face.
 */
class TimerOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var card: CardView? = null
    private var bubble: BubbleView? = null
    private val onChange: () -> Unit = { refresh() }

    init {
        FocusTimer.addListener(onChange)
        // A timer left running before Android closed OrbitSearch: bring its bubble back.
        FocusTimer.restore(ctx)
        if (FocusTimer.state == FocusTimer.State.RUNNING || FocusTimer.state == FocusTimer.State.PAUSED) showBubble()
    }

    private fun themed(): Context = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_DayNight)

    /** Room around the content so its shadow isn't clipped by the window edge. */
    private val shadowPad get() = ctx.dpi(16f)

    /** Both windows share this corner, so the card grows out of the bubble. */
    private fun cornerParams(focusable: Boolean, title: String) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = ctx.dpi(24f) - shadowPad
        y = ctx.dpi(72f) - shadowPad // clear of the taskbar / gesture bar
        windowAnimations = 0 // animated by the views themselves
        this.title = title
    }

    fun showCard() {
        if (card != null) return
        removeBubble(animated = true)
        val v = CardView(themed(), Palette.current(ctx))
        val lp = cornerParams(focusable = true, title = "OrbitSearch timer")
        Motion.preferFastest(
            lp, ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        )
        try {
            wm.addView(v, lp)
            card = v
            v.enter()
        } catch (_: Exception) {}
    }

    /** Card → bubble. */
    fun collapse() {
        val v = card ?: return
        card = null
        if (FocusTimer.state == FocusTimer.State.DONE || FocusTimer.state == FocusTimer.State.IDLE) {
            FocusTimer.dismissDone()
            v.shrink { remove(v) }
            return
        }
        v.shrink {
            remove(v)
            showBubble()
        }
    }

    private fun showBubble() {
        if (bubble != null || FocusTimer.state == FocusTimer.State.IDLE) return
        val v = BubbleView(themed(), Palette.current(ctx)) { showCard() }
        try {
            wm.addView(v, cornerParams(focusable = false, title = "OrbitSearch timer bubble"))
            bubble = v
            v.enter()
        } catch (_: Exception) {}
    }

    private fun removeBubble(animated: Boolean) {
        val v = bubble ?: return
        bubble = null
        if (animated) v.leave { remove(v) } else remove(v)
    }

    private fun remove(v: View) {
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    private fun refresh() {
        when (FocusTimer.state) {
            FocusTimer.State.IDLE -> {
                card?.let { c -> card = null; c.shrink { remove(c) } }
                removeBubble(animated = true)
            }
            FocusTimer.State.DONE -> {
                // Time's up: open the card so you see it.
                removeBubble(animated = true)
                if (card == null) showCard() else card?.update()
            }
            else -> {
                card?.update()
                bubble?.update()
            }
        }
    }

    fun hideAll() {
        card?.let { remove(it) }; card = null
        bubble?.let { remove(it) }; bubble = null
    }

    private fun surface(c: Context, pal: Palette, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = c.dp(radiusDp)
        setColor(if (pal.dark) 0xF2202024.toInt() else 0xF7FFFFFF.toInt())
        setStroke(maxOf(1, c.dpi(1f) / 2 + 1), if (pal.dark) 0x17FFFFFF else 0x140B1220)
    }

    // ---------- card ----------

    private inner class CardView(c: Context, private val pal: Palette) : FrameLayout(c) {
        private val shell = LinearLayout(c)
        private val dot = View(c)
        private val status = TextView(c)
        private val face = TimerFace(c, pal, TimerFace.Style.DIAL)
        private val dots = FaceDots(c, pal, face.face) { pick(it) }
        private val restoreHint = Runnable { update() }
        private val primary = button(c, pal, filled = true)
        private val plus = button(c, pal, filled = false)
        private val stop = IconButton(c, pal, IconButton.Kind.STOP, boxed = true)
        private val hint = TextView(c)

        init {
            setPadding(shadowPad, shadowPad, shadowPad, shadowPad)
            clipToPadding = false
            shell.apply {
                orientation = LinearLayout.VERTICAL
                background = surface(c, pal, 26f)
                elevation = c.dp(18f)
                setPadding(c.dpi(20f), c.dpi(12f), c.dpi(12f), c.dpi(18f))
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    v.pivotX = v.width.toFloat(); v.pivotY = v.height.toFloat() // grow from the corner
                }
            }
            addView(shell, LayoutParams(c.dpi(340f), LayoutParams.WRAP_CONTENT))

            // Header: ● Focus ..................... ⌄
            val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.accent) }
            head.addView(dot, LinearLayout.LayoutParams(c.dpi(8f), c.dpi(8f)).apply { marginEnd = c.dpi(8f) })
            status.apply {
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                letterSpacing = 0.02f
            }
            head.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val chevron = IconButton(c, pal, IconButton.Kind.CHEVRON, boxed = false).apply {
                contentDescription = "Minimise timer"
                setOnClickListener { collapse() }
            }
            head.addView(chevron, LinearLayout.LayoutParams(c.dpi(44f), c.dpi(44f)))
            shell.addView(head)

            // Body: ring on the left, controls on the right.
            val body = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val faceCol = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            faceCol.addView(face)
            faceCol.addView(dots, LinearLayout.LayoutParams(c.dpi(120f), c.dpi(40f)))
            face.contentDescription = "Timer face. Swipe left or right to change"
            face.setOnTouchListener(swipe)
            body.addView(faceCol)
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            col.addView(primary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, c.dpi(44f)))
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(plus, LinearLayout.LayoutParams(0, c.dpi(44f), 1f))
            stop.contentDescription = "Stop timer"
            row.addView(stop, LinearLayout.LayoutParams(c.dpi(44f), c.dpi(44f)).apply { marginStart = c.dpi(10f) })
            col.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = c.dpi(10f)
            })
            hint.apply {
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, c.dpi(10f), 0, 0)
            }
            col.addView(hint)
            body.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = c.dpi(20f); marginEnd = c.dpi(6f)
            })
            shell.addView(body)

            primary.setOnClickListener { onPrimary() }
            plus.setOnClickListener { FocusTimer.addMinutes(context, 5) }
            stop.setOnClickListener { onStop() }
            isFocusableInTouchMode = true
            update()
        }

        /** Horizontal swipe on the face steps through the looks. */
        private val swipe = object : View.OnTouchListener {
            private var x0 = 0f
            private var y0 = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y }
                    MotionEvent.ACTION_UP -> {
                        val dx = e.x - x0
                        if (abs(dx) > v.context.dp(24f) && abs(dx) > abs(e.y - y0)) pick(face.face.next(if (dx < 0) 1 else -1))
                    }
                }
                return true
            }
        }

        private fun pick(f: TimerFaceKind) {
            if (f == face.face) return
            Prefs.setTimerFace(context, f)
            face.setFace(f)
            dots.select(f)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            hint.text = f.label
            removeCallbacks(restoreHint)
            postDelayed(restoreHint, 1400)
        }

        private fun onPrimary() = when (FocusTimer.state) {
            FocusTimer.State.DONE -> FocusTimer.again(context)
            else -> FocusTimer.toggle(context)
        }

        private fun onStop() {
            if (FocusTimer.state == FocusTimer.State.DONE) collapse() else FocusTimer.end(context)
        }

        fun update() {
            val st = FocusTimer.state
            status.text = when (st) {
                FocusTimer.State.DONE -> "Done"
                FocusTimer.State.PAUSED -> "Paused"
                else -> FocusTimer.label
            }
            primary.text = when (st) {
                FocusTimer.State.RUNNING -> "Pause"
                FocusTimer.State.PAUSED -> "Resume"
                else -> "Again"
            }
            plus.text = "+  5 min"
            stop.contentDescription = if (st == FocusTimer.State.DONE) "Close timer" else "Stop timer"
            hint.text = if (st == FocusTimer.State.DONE) "Enter again · Esc closes" else "Space pauses · ← → face"
            face.poke()
        }

        fun enter() {
            requestFocus()
            shell.alpha = 0f
            shell.scaleX = 0.18f; shell.scaleY = 0.26f
            shell.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(420).setInterpolator(Motion.springOut).withLayer().start()
        }

        /** Shrinks back into the corner, where the bubble appears. */
        fun shrink(done: () -> Unit) {
            shell.animate().alpha(0f).scaleX(0.18f).scaleY(0.26f)
                .setDuration(240).setInterpolator(Motion.easeIn).withLayer().withEndAction(done).start()
        }

        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
            when (e.keyCode) {
                KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> { collapse(); return true }
                KeyEvent.KEYCODE_SPACE -> { onPrimary(); return true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { onPrimary(); return true }
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                    FocusTimer.addMinutes(context, 5); return true
                }
                KeyEvent.KEYCODE_DEL -> if (e.isCtrlPressed) { FocusTimer.end(context); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { pick(face.face.next(-1)); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { pick(face.face.next(1)); return true }
            }
            return super.dispatchKeyEvent(e)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { collapse(); return true }
            return super.dispatchTouchEvent(ev)
        }
    }

    // ---------- bubble ----------

    private inner class BubbleView(c: Context, pal: Palette, onTap: () -> Unit) : FrameLayout(c) {
        private val face = TimerFace(c, pal, TimerFace.Style.BUBBLE)
        private val body = FrameLayout(c)

        init {
            setPadding(shadowPad, shadowPad, shadowPad, shadowPad)
            clipToPadding = false
            body.apply {
                background = surface(c, pal, 30f)
                elevation = c.dp(10f)
                addView(face, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                isClickable = true
                setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onTap() }
                contentDescription = "Focus timer. Tap to open"
                // Mouse / trackpad: grow a touch on hover.
                setOnHoverListener { v, e ->
                    when (e.action) {
                        MotionEvent.ACTION_HOVER_ENTER -> v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(160).start()
                        MotionEvent.ACTION_HOVER_EXIT -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    }
                    false
                }
            }
            addView(body, LayoutParams(c.dpi(60f), c.dpi(60f)))
        }

        fun update() = face.poke()

        fun enter() {
            body.alpha = 0f
            body.scaleX = 0.6f; body.scaleY = 0.6f
            body.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(Motion.ENTER_MS + 60).setInterpolator(Motion.springOut).start()
        }

        fun leave(done: () -> Unit) {
            body.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f)
                .setDuration(120).setInterpolator(Motion.easeIn).withEndAction(done).start()
        }
    }

    // ---------- small pieces ----------

    private fun button(c: Context, pal: Palette, filled: Boolean) = TextView(c).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        setTextColor(if (filled) pal.onAccent else pal.text)
        background = GradientDrawable().apply {
            cornerRadius = c.dp(14f)
            setColor(if (filled) pal.accent else if (pal.dark) 0x10FFFFFF else 0x0D0B1220)
            if (!filled) setStroke(maxOf(1, c.dpi(1f) / 2 + 1), if (pal.dark) 0x14FFFFFF else 0x140B1220)
        }
        isClickable = true
        isFocusable = false
        setOnTouchListener(pressScale)
    }
}

/** Buttons dip slightly while pressed. */
private val pressScale = View.OnTouchListener { v, e ->
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
    }
    false
}

/** A stroke icon button: the minimise chevron, or the square stop button. */
private class IconButton(c: Context, private val pal: Palette, private val kind: Kind, boxed: Boolean) : View(c) {
    enum class Kind { CHEVRON, STOP }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = c.dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = if (kind == Kind.CHEVRON) pal.sub else pal.text
    }
    private val path = Path()

    init {
        isClickable = true
        isFocusable = false
        background = GradientDrawable().apply {
            cornerRadius = c.dp(if (boxed) 14f else 22f)
            setColor(if (boxed) (if (pal.dark) 0x10FFFFFF else 0x0D0B1220) else 0)
            if (boxed) setStroke(maxOf(1, c.dpi(1f) / 2 + 1), if (pal.dark) 0x14FFFFFF else 0x140B1220)
        }
        setOnTouchListener(pressScale)
    }

    override fun onDraw(canvas: Canvas) {
        val s = context.dp(if (kind == Kind.CHEVRON) 20f else 16f) / 24f // icons drawn on a 24-unit grid
        canvas.save()
        canvas.translate(width / 2f - 12f * s, height / 2f - 12f * s)
        path.reset()
        if (kind == Kind.CHEVRON) {
            path.moveTo(6f * s, 9f * s); path.lineTo(12f * s, 15f * s); path.lineTo(18f * s, 9f * s)
            canvas.drawPath(path, paint)
        } else {
            val r = 2f * s
            canvas.drawRoundRect(6f * s, 6f * s, 18f * s, 18f * s, r, r, paint)
        }
        canvas.restore()
    }
}

/** Three dots under the face; the current one is a short red pill. Tap a dot to pick that face. */
private class FaceDots(
    c: Context, private val pal: Palette, initial: TimerFaceKind, private val onPick: (TimerFaceKind) -> Unit,
) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var current = initial
    private var from = initial
    private var changedAt = 0L

    init {
        isClickable = true
        isFocusable = false
        contentDescription = "Timer face: " + initial.label
    }

    fun select(f: TimerFaceKind) {
        if (f == current) return
        from = current
        current = f
        changedAt = android.os.SystemClock.uptimeMillis()
        contentDescription = "Timer face: " + f.label
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val n = TimerFaceKind.entries.size
            val i = (e.x / width * n).toInt().coerceIn(0, n - 1)
            onPick(TimerFaceKind.entries[i])
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val n = TimerFaceKind.entries.size
        val t = ((android.os.SystemClock.uptimeMillis() - changedAt) / 220f).coerceIn(0f, 1f)
        val e = Motion.springOut.getInterpolation(t)
        val dot = context.dp(6f)
        val pill = context.dp(16f)
        val cy = height / 2f
        for (i in 0 until n) {
            val cx = width * (i + 0.5f) / n
            val k = TimerFaceKind.entries[i]
            val w = when (k) {
                current -> dot + (pill - dot) * e
                from -> pill - (pill - dot) * e
                else -> dot
            }
            val on = if (k == current) e else if (k == from) 1f - e else 0f
            paint.color = blend(if (pal.dark) 0x40FFFFFF else 0x400B1220, pal.accent, on)
            canvas.drawRoundRect(cx - w / 2, cy - dot / 2, cx + w / 2, cy + dot / 2, dot / 2, dot / 2, paint)
        }
        if (t < 1f) postInvalidateOnAnimation()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a ushr sh) and 0xFF) + (((b ushr sh) and 0xFF) - ((a ushr sh) and 0xFF)) * t).toInt() shl sh
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}
