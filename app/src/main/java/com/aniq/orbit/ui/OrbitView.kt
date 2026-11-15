package com.aniq.orbit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aniq.orbit.Launcher
import com.aniq.orbit.Prefs
import com.aniq.orbit.ai.AiChat
import com.aniq.orbit.ai.AiSettings
import com.aniq.orbit.ai.ChatMessage
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
    opaque: Boolean,
) : FrameLayout(context) {

    interface Host { fun dismiss() }

    private val pal = Palette.current(context)
    private val panel = GlassPanel(context, pal, opaque)
    private val input = EditText(context)
    private val chips = ArrayList<TextView>()
    private val rows = ArrayList<RowView>()
    private val empty = TextView(context)
    private var results: List<Item> = emptyList()
    private var selected = 0
    private var filter = Filter.ALL
    private val onIndexChanged: () -> Unit = { if (!quick) refresh() }

    // Quick AI (Raycast-style): Tab answers inside the panel instead of the result list.
    private var quick = false
    private lateinit var chipsView: View
    private lateinit var listView: LinearLayout
    private val answerBox = LinearLayout(context)
    private val answerHeader = TextView(context)
    private val answerText = TextView(context)
    private val answerScroll = MaxHeightScroll(context)
    private val footerHint = TextView(context)

    init {
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(searchRow())
        chipsView = chipRow()
        panel.addView(chipsView)
        panel.addView(divider())
        panel.addView(buildAnswerBox())

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
        listView = list
        panel.addView(list)
        panel.addView(divider())
        panel.addView(footer())

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!quick) refresh() }
        })
        // On-screen keyboard "Go" key behaves like Enter.
        input.setOnEditorActionListener { _, _, _ ->
            if (quick) followUp() else open(selected, Launcher.Mode.NORMAL)
            true
        }
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
        addView(footerHint.apply {
            text = SEARCH_HINT
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
    }

    private fun buildAnswerBox(): View = answerBox.apply {
        orientation = LinearLayout.VERTICAL
        visibility = GONE
        setPadding(context.dpi(22f), context.dpi(4f), context.dpi(22f), context.dpi(8f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageDrawable(StrokeIcon(Glyph.SPARK, pal.sub)) },
                LinearLayout.LayoutParams(context.dpi(16f), context.dpi(16f)).apply { marginEnd = context.dpi(8f) })
            addView(answerHeader.apply {
                setTextColor(pal.sub)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        })
        answerText.apply {
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(false)
            setPadding(0, context.dpi(10f), 0, context.dpi(6f))
        }
        answerScroll.addView(answerText)
        answerScroll.isVerticalScrollBarEnabled = true
        addView(answerScroll)
    }

    /** Borderless: sections are separated by space, not lines. */
    private fun divider(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpi(4f))
    }

    private fun styleChips() {
        chips.forEachIndexed { i, c ->
            val on = Filter.entries[i] == filter
            (c.background as GradientDrawable).setColor(if (on) pal.selection else pal.chip)
            c.setTextColor(if (on) pal.onSelection else pal.text)
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
        if (item is Item.Ask) { startQuickAi(item.query); return }
        if (Launcher.open(context, item, mode)) host.dismiss()
    }

    // ---------- Quick AI ----------

    private fun startQuickAi(raw: String) {
        val q = raw.trim().let { Regex("^ask\\s+", RegexOption.IGNORE_CASE).replace(it, "") }
        if (q.isEmpty()) return
        quick = true
        AiChat.clear()
        chipsView.visibility = GONE
        listView.visibility = GONE
        answerBox.visibility = VISIBLE
        input.setText("")
        input.hint = "Ask a follow-up…"
        footerHint.text = QUICK_HINT
        val p = AiSettings.provider(context)
        answerHeader.text = "Quick AI  ·  ${p.label}  ·  ${AiSettings.model(context, p).ifBlank { "no model set" }}"
        ask(q)
    }

    private fun ask(q: String) {
        if (!AiSettings.ready(context)) {
            AiChat.messages += ChatMessage(true, q)
            AiChat.messages += ChatMessage(false,
                "⚠ AI isn't set up yet. Press Ctrl + , to open ORBIT settings, choose a provider and paste your API key.")
            renderAnswer()
            return
        }
        AiChat.send(context, q, onUpdate = { renderAnswer() }, onDone = { renderAnswer() })
    }

    private fun followUp() {
        val q = input.text?.toString()?.trim().orEmpty()
        if (q.isEmpty() || AiChat.busy) return
        input.setText("")
        ask(q)
    }

    private fun exitQuickAi() {
        AiChat.clear()
        quick = false
        answerBox.visibility = GONE
        chipsView.visibility = VISIBLE
        listView.visibility = VISIBLE
        input.hint = "Search apps, settings, maths…"
        footerHint.text = SEARCH_HINT
        input.setText("")
        refresh()
    }

    private fun renderAnswer() {
        val sb = SpannableStringBuilder()
        AiChat.messages.forEachIndexed { i, m ->
            if (i > 0) sb.append(if (m.fromUser) "\n\n" else "\n")
            val start = sb.length
            if (m.fromUser) {
                sb.append(m.text)
                sb.setSpan(ForegroundColorSpan(pal.sub), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.append(if (m.text.isEmpty() && AiChat.busy) "Thinking…" else plain(m.text))
            }
        }
        answerText.text = sb
        answerScroll.post { answerScroll.scrollTo(0, answerText.height) }
    }

    /** Light markdown clean-up so answers read well as plain text. */
    private fun plain(md: String): String = md.lines().joinToString("\n") { line ->
        var l = line
        l = l.replace(Regex("^#{1,6}\\s+"), "")
        l = l.replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
        l.replace("**", "").replace("__", "").replace("`", "")
    }

    private fun copyAnswer() {
        val last = AiChat.messages.lastOrNull { !it.fromUser }?.text ?: return
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("ORBIT AI", plain(last)))
    }

    private fun quickKey(event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> { exitQuickAi(); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> { followUp(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { answerScroll.smoothScrollBy(0, context.dpi(80f)); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { answerScroll.smoothScrollBy(0, -context.dpi(80f)); return true }
            KeyEvent.KEYCODE_C -> if (ctrl && input.selectionStart == input.selectionEnd) { copyAnswer(); return true }
            KeyEvent.KEYCODE_N -> if (ctrl) {
                AiChat.clear()
                renderAnswer()
                input.hint = "Ask a new question…"
                return true
            }
            KeyEvent.KEYCODE_COMMA -> if (ctrl) {
                if (Launcher.open(context, Item.OrbitSettings, Launcher.Mode.NORMAL)) host.dismiss()
                return true
            }
        }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (quick && quickKey(event)) return true
        if (quick) return super.dispatchKeyEvent(event)
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
                if (ctrl) {
                    // Ctrl + Tab / Ctrl + Shift + Tab: switch filter.
                    val n = Filter.entries.size
                    val step = if (event.isShiftPressed) n - 1 else 1
                    setFilter(Filter.entries[(filter.ordinal + step) % n])
                } else {
                    // Tab: Quick AI on whatever you've typed.
                    startQuickAi(input.text?.toString().orEmpty())
                }
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
        animateIn()
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

    // ---------- motion ----------
    // Only alpha, scale and translation are animated: these run on the GPU's render
    // properties, so every frame is cheap and can hit 120 Hz. The window also asks the
    // display for its highest refresh rate (see Motion.kt).

    private var closing = false

    private fun animateIn() {
        val rise = context.dp(14f)
        panel.alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.translationY = -rise
        panel.pivotY = 0f
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(Motion.ENTER_MS)
            .setInterpolator(Motion.springOut)
            .withLayer()
            .start()
        // Results settle in just after the panel, one after another.
        for (i in rows.indices) {
            val r = rows[i]
            if (r.visibility != VISIBLE) continue
            r.alpha = 0f
            r.translationY = context.dp(6f)
            r.animate().alpha(1f).translationY(0f)
                .setStartDelay(60L + i * 14L)
                .setDuration(180)
                .setInterpolator(Motion.easeOut)
                .start()
        }
    }

    /** Fades and lifts the panel away, then calls [done] (which removes the window). */
    fun animateOut(done: () -> Unit) {
        if (closing) return
        closing = true
        panel.animate().cancel()
        panel.animate()
            .alpha(0f).scaleX(0.97f).scaleY(0.97f).translationY(-context.dp(8f))
            .setStartDelay(0)
            .setDuration(Motion.EXIT_MS)
            .setInterpolator(Motion.easeIn)
            .withLayer()
            .withEndAction(done)
            .start()
    }

    override fun onDetachedFromWindow() {
        AppIndex.removeListener(onIndexChanged)
        if (quick) AiChat.clear()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val SEARCH_HINT = "↵ Open    Tab Ask AI    Ctrl ↵ Pop-up    Esc Close"
        private const val QUICK_HINT = "↵ / Tab Follow-up    Ctrl C Copy    Ctrl N New    Esc Back"
    }
}

/** Scrolls when the answer gets long, so the panel never grows past half the screen. */
class MaxHeightScroll(context: Context) : android.widget.ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
    }
}
