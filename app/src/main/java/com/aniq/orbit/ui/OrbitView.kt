package com.aniq.orbit.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aniq.orbit.Launcher
import com.aniq.orbit.Prefs
import com.aniq.orbit.search.AppIndex
import com.aniq.orbit.search.Filter
import com.aniq.orbit.search.Glyph
import com.aniq.orbit.search.Item
import com.aniq.orbit.search.SearchEngine

/**
 * The whole search panel. Hosted either in the hotkey overlay (accessibility window)
 * or in SearchActivity. Keyboard first: Enter always opens the highlighted result,
 * which is the first result unless you move with the arrow keys.
 */
class OrbitView(
    context: Context,
    private val host: Host,
    drawFill: Boolean,
    opaque: Boolean,
) : FrameLayout(context) {

    interface Host { fun dismiss() }

    private val pal = Palette.current(context)
    private val panel = GlassPanel(context, pal, drawFill, opaque)
    private val input = EditText(context)
    private val chips = ArrayList<TextView>()
    private val rows = ArrayList<RowView>()
    private val empty = TextView(context)
    private var results: List<Item> = emptyList()
    private var selected = 0
    private var filter = Filter.ALL
    private val onIndexChanged: () -> Unit = { refresh() }

    init {
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(searchRow())
        panel.addView(chipRow())
        panel.addView(divider())

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpi(12f), context.dpi(8f), context.dpi(12f), context.dpi(8f))
        }
        for (i in 0 until SearchEngine.MAX) {
            val r = RowView(context, pal)
            r.setOnClickListener { open(i, Launcher.Mode.NORMAL) }
            r.setOnHoverListener { _, e ->
                if (e.action == MotionEvent.ACTION_HOVER_ENTER && i < results.size) select(i)
                false
            }
            rows += r
            list.addView(r)
        }
        empty.apply {
            text = "No matches"
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(context.dpi(12f), context.dpi(14f), 0, context.dpi(14f))
            visibility = GONE
        }
        list.addView(empty)
        panel.addView(list)
        panel.addView(divider())
        panel.addView(footer())

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        // On-screen keyboard "Go" key behaves like Enter.
        input.setOnEditorActionListener { _, _, _ -> open(selected, Launcher.Mode.NORMAL); true }
        refresh()
    }

    // ---------- building ----------

    private fun searchRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dpi(22f), context.dpi(16f), context.dpi(18f), context.dpi(12f))
        }
        val icon = ImageView(context).apply { setImageDrawable(StrokeIcon(Glyph.SEARCH, pal.sub)) }
        row.addView(icon, LinearLayout.LayoutParams(context.dpi(22f), context.dpi(22f)).apply {
            marginEnd = context.dpi(14f)
        })
        input.apply {
            background = null
            hint = "Search apps, settings, maths…"
            setHintTextColor(pal.sub)
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(0, 0, 0, 0)
            contentDescription = "Search"
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val badge = TextView(context).apply {
            text = Prefs.hotkey(context).label
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(context.dpi(9f), context.dpi(4f), context.dpi(9f), context.dpi(4f))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(8f)
                setColor(pal.chip)
                setStroke(context.dpi(1f), pal.divider)
            }
        }
        row.addView(badge)
        return row
    }

    private fun chipRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dpi(22f), 0, context.dpi(22f), context.dpi(12f))
        }
        for (f in Filter.entries) {
            val c = TextView(context).apply {
                text = f.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(context.dpi(12f), context.dpi(6f), context.dpi(12f), context.dpi(6f))
                background = GradientDrawable().apply { cornerRadius = context.dp(999f) }
                setOnClickListener { setFilter(f) }
            }
            chips += c
            row.addView(c, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = context.dpi(8f) })
        }
        row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(TextView(context).apply {
            text = "Tab to switch"
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        styleChips()
        return row
    }

    private fun footer(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dpi(22f), context.dpi(10f), context.dpi(22f), context.dpi(12f))
        addView(TextView(context).apply {
            text = "ORBIT"
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            paint.isFakeBoldText = true
        })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(TextView(context).apply {
            text = "↵ Open    Ctrl ↵ Pop-up    Shift ↵ Side by side    Esc Close"
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(pal.divider)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(1f))
    }

    private fun styleChips() {
        chips.forEachIndexed { i, c ->
            val on = Filter.entries[i] == filter
            (c.background as GradientDrawable).setColor(if (on) pal.accent else pal.chip)
            c.setTextColor(if (on) pal.onAccent else pal.text)
        }
    }

    // ---------- behaviour ----------

    private fun refresh() {
        results = SearchEngine.search(context, input.text?.toString().orEmpty(), filter)
        selected = 0 // Enter opens the first result by default.
        for (i in rows.indices) {
            if (i < results.size) {
                rows[i].visibility = VISIBLE
                rows[i].bind(results[i])
            } else {
                rows[i].visibility = GONE
            }
        }
        empty.visibility = if (results.isEmpty()) VISIBLE else GONE
        paintSelection()
    }

    private fun select(i: Int) {
        if (i == selected) return
        selected = i
        paintSelection()
    }

    private fun paintSelection() {
        for (i in results.indices) rows[i].setSelected(i == selected, i)
    }

    private fun setFilter(f: Filter) {
        filter = f
        styleChips()
        refresh()
    }

    private fun open(i: Int, mode: Launcher.Mode) {
        val item = results.getOrNull(i) ?: return
        if (Launcher.open(context, item, mode)) host.dismiss()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val ctrl = event.isCtrlPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> { host.dismiss(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (results.isNotEmpty()) select((selected + 1) % results.size)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (results.isNotEmpty()) select((selected - 1 + results.size) % results.size)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val mode = when {
                    ctrl -> Launcher.Mode.POPUP
                    event.isShiftPressed -> Launcher.Mode.SIDE_BY_SIDE
                    else -> Launcher.Mode.NORMAL
                }
                open(selected, mode)
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                val n = Filter.entries.size
                val step = if (event.isShiftPressed) n - 1 else 1
                setFilter(Filter.entries[(filter.ordinal + step) % n])
                return true
            }
            KeyEvent.KEYCODE_DEL -> if (ctrl) { input.setText(""); return true }
            KeyEvent.KEYCODE_COMMA -> if (ctrl) {
                if (Launcher.open(context, Item.OrbitSettings, Launcher.Mode.NORMAL)) host.dismiss()
                return true
            }
            KeyEvent.KEYCODE_C -> if (ctrl && input.selectionStart == input.selectionEnd) {
                val calc = results.getOrNull(selected) as? Item.Calc
                if (calc != null) { open(selected, Launcher.Mode.NORMAL); return true }
            }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> if (ctrl) {
                open(event.keyCode - KeyEvent.KEYCODE_1, Launcher.Mode.NORMAL)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_OUTSIDE) { host.dismiss(); return true }
        return super.dispatchTouchEvent(ev)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AppIndex.addListener(onIndexChanged)
        panel.alpha = 0f
        panel.scaleX = 0.97f
        panel.scaleY = 0.97f
        panel.translationY = -context.dp(8f)
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(140).setInterpolator(DecelerateInterpolator()).start()
        input.requestFocus()
        // Only pop the on-screen keyboard when no physical keyboard is attached.
        val cfg = resources.configuration
        val hasKeys = cfg.keyboard != Configuration.KEYBOARD_NOKEYS &&
            cfg.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        if (!hasKeys) post {
            context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDetachedFromWindow() {
        AppIndex.removeListener(onIndexChanged)
        super.onDetachedFromWindow()
    }
}
