package com.aniq.orbit.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.aniq.orbit.tools.FocusTimer

/**
 * The focus timer's own glass UI, in two shapes:
 *  - a card in the middle of the screen (big ring, rolling digits, Pause / +5 / End),
 *  - a small pill at the top that stays out of your way, like a Dynamic Island.
 * Esc or tapping outside shrinks the card into the pill; tapping the pill opens the card.
 * Only GPU-cheap properties are animated, and the window asks for the fastest refresh rate.
 */
class TimerOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var card: CardView? = null
    private var pill: PillView? = null
    private val onChange: () -> Unit = { refresh() }

    init { FocusTimer.addListener(onChange) }

    private fun themed(): Context = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_DayNight)

    fun showCard() {
        if (card != null) return
        removePill(animated = true)
        val pal = Palette.current(ctx)
        val v = CardView(themed(), pal)
        val reduced = Palette.reduced(ctx)
        val blur = !reduced && wm.isCrossWindowBlurEnabled
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        if (blur) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val lp = WindowManager.LayoutParams(
            ctx.dpi(400f), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = if (pal.dark) 0.28f else 0.14f
            if (blur) blurBehindRadius = ctx.dpi(30f)
            windowAnimations = 0
            title = "ORBIT timer"
        }
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

    /** Card → pill. */
    fun collapse() {
        val v = card ?: return
        card = null
        if (FocusTimer.state == FocusTimer.State.DONE || FocusTimer.state == FocusTimer.State.IDLE) {
            FocusTimer.dismissDone()
            v.leave { remove(v) }
            return
        }
        v.shrinkAway {
            remove(v)
            showPill()
        }
    }

    private fun showPill() {
        if (pill != null || FocusTimer.state == FocusTimer.State.IDLE) return
        val pal = Palette.current(ctx)
        val v = PillView(themed(), pal) { showCard() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = ctx.dpi(10f)
            windowAnimations = 0
            title = "ORBIT timer pill"
        }
        try {
            wm.addView(v, lp)
            pill = v
            v.enter()
        } catch (_: Exception) {}
    }

    private fun removePill(animated: Boolean) {
        val v = pill ?: return
        pill = null
        if (animated) v.leave { remove(v) } else remove(v)
    }

    private fun remove(v: View) {
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }

    private fun refresh() {
        when (FocusTimer.state) {
            FocusTimer.State.IDLE -> {
                card?.let { c -> card = null; c.leave { remove(c) } }
                removePill(animated = true)
            }
            FocusTimer.State.DONE -> {
                // Time's up: bring the card back so you see it.
                removePill(animated = true)
                if (card == null) showCard() else card?.update()
            }
            else -> {
                card?.update()
                pill?.update()
            }
        }
    }

    fun hideAll() {
        card?.let { remove(it) }; card = null
        pill?.let { remove(it) }; pill = null
    }

    // ---------- card ----------

    private inner class CardView(c: Context, private val pal: Palette) : FrameLayout(c) {
        private val glass = GlassPanel(c, pal, opaque = Palette.reduced(c) || !wm.isCrossWindowBlurEnabled)
        private val header = TextView(c)
        private val face = TimerFace(c, pal, compact = false)
        private val primary = pillButton(c, filled = true)
        private val plus = pillButton(c, filled = false)
        private val end = pillButton(c, filled = false)
        private val hint = TextView(c)

        init {
            glass.gravity = Gravity.CENTER_HORIZONTAL
            glass.setPadding(c.dpi(24f), c.dpi(22f), c.dpi(24f), c.dpi(18f))
            addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            header.apply {
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                letterSpacing = 0.1f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
            }
            glass.addView(header)
            glass.addView(face, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = c.dpi(8f); bottomMargin = c.dpi(14f)
            })
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (b in listOf(primary, plus, end)) row.addView(b, LinearLayout.LayoutParams(0, c.dpi(48f), 1f).apply {
                marginStart = c.dpi(4f); marginEnd = c.dpi(4f)
            })
            glass.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            hint.apply {
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, c.dpi(12f), 0, 0)
            }
            glass.addView(hint)

            primary.setOnClickListener { onPrimary() }
            plus.setOnClickListener { FocusTimer.addMinutes(context, 5) }
            end.setOnClickListener { onSecondary() }
            isFocusableInTouchMode = true
            update()
        }

        private fun onPrimary() = when (FocusTimer.state) {
            FocusTimer.State.DONE -> FocusTimer.again(context)
            else -> FocusTimer.toggle(context)
        }

        private fun onSecondary() {
            if (FocusTimer.state == FocusTimer.State.DONE) collapse() else FocusTimer.end(context)
        }

        fun update() {
            val st = FocusTimer.state
            header.text = when (st) {
                FocusTimer.State.DONE -> "${FocusTimer.label.uppercase()} COMPLETE"
                FocusTimer.State.PAUSED -> "${FocusTimer.label.uppercase()} · PAUSED"
                else -> FocusTimer.label.uppercase() + if (FocusTimer.dndActive()) " · DO NOT DISTURB ON" else ""
            }
            face.captionText = when (st) {
                FocusTimer.State.DONE -> "Nice work"
                FocusTimer.State.PAUSED -> "Paused"
                else -> ""
            }
            primary.text = when (st) {
                FocusTimer.State.RUNNING -> "Pause"
                FocusTimer.State.PAUSED -> "Resume"
                else -> "Again"
            }
            plus.text = "+5 min"
            end.text = if (st == FocusTimer.State.DONE) "Close" else "End"
            hint.text = if (st == FocusTimer.State.DONE) "↵ Again    Esc Close"
            else "Space Pause    +  Add 5 min    Esc Minimise    Ctrl ⌫ End"
            face.poke()
        }

        fun enter() {
            requestFocus()
            glass.alpha = 0f
            glass.scaleX = 0.9f; glass.scaleY = 0.9f
            glass.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(Motion.ENTER_MS + 60).setInterpolator(Motion.springOut).withLayer().start()
        }

        fun leave(done: () -> Unit) {
            glass.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(Motion.EXIT_MS).setInterpolator(Motion.easeIn).withLayer().withEndAction(done).start()
        }

        /** Shrinks up towards where the pill will appear, then hands over to it. */
        fun shrinkAway(done: () -> Unit) {
            val toTop = -(rootView.top + height / 2f) - context.dp(200f)
            glass.pivotY = 0f
            glass.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).translationY(toTop * 0.35f)
                .setDuration(220).setInterpolator(Motion.easeIn).withLayer().withEndAction(done).start()
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
            }
            return super.dispatchKeyEvent(e)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { collapse(); return true }
            return super.dispatchTouchEvent(ev)
        }
    }

    // ---------- pill ----------

    private inner class PillView(c: Context, pal: Palette, onTap: () -> Unit) : FrameLayout(c) {
        private val face = TimerFace(c, pal, compact = true)
        private val body = LinearLayout(c)

        init {
            body.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dpi(12f), c.dpi(8f), c.dpi(16f), c.dpi(8f))
                background = GradientDrawable().apply {
                    cornerRadius = c.dp(26f)
                    setColor(if (pal.dark) 0xF01C1C1E.toInt() else 0xF5FFFFFF.toInt())
                }
                elevation = c.dp(8f)
                addView(face)
            }
            addView(body, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(c.dpi(10f), c.dpi(4f), c.dpi(10f), c.dpi(14f))
            })
            body.setOnClickListener { onTap() }
            body.contentDescription = "Timer. Tap to open"
        }

        fun update() = face.poke()

        fun enter() {
            body.alpha = 0f
            body.scaleX = 0.6f; body.scaleY = 0.8f
            body.translationY = -c.dp(12f)
            body.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(Motion.ENTER_MS + 40).setInterpolator(Motion.springOut).start()
        }

        fun leave(done: () -> Unit) {
            body.animate().alpha(0f).scaleX(0.7f).scaleY(0.8f)
                .setDuration(Motion.EXIT_MS).setInterpolator(Motion.easeIn).withEndAction(done).start()
        }

        private val c get() = context
    }

    private fun pillButton(c: Context, filled: Boolean) = TextView(c).apply {
        val pal = Palette.current(c)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        paint.isFakeBoldText = true
        setTextColor(if (filled) pal.onAccent else pal.text)
        background = GradientDrawable().apply {
            cornerRadius = c.dp(24f)
            setColor(if (filled) pal.accent else pal.chip)
        }
        isClickable = true
        isFocusable = false
    }
}
