package com.aniq.orbit.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aniq.orbit.Launcher
import com.aniq.orbit.Prefs
import com.aniq.orbit.search.AppIndex
import com.aniq.orbit.search.CalcHistory
import com.aniq.orbit.search.FilePreview
import com.aniq.orbit.search.Filter
import com.aniq.orbit.search.Glyph
import com.aniq.orbit.search.Item
import com.aniq.orbit.search.SearchEngine

/**
 * The whole search panel. Hosted in the hotkey overlay (kept ready and reused, so opening is
 * instant) or in SearchActivity. Keyboard first: Enter always opens the highlighted result,
 * which is the first result unless you move with the arrow keys.
 *
 * Modes: SEARCH (results), CHEAT (Ctrl + / shortcuts), PREVIEW (Space on a file).
 */
class OrbitView(
    context: Context,
    private val host: Host,
    opaque: Boolean,
) : FrameLayout(context) {

    interface Host { fun dismiss() }

    private enum class Mode { SEARCH, CHEAT, PREVIEW }

    val pal = Palette.current(context)
    private val panel = GlassPanel(context, pal, opaque)
    private val input = EditText(context)
    private val chips = ArrayList<TextView>()
    private val rows = ArrayList<RowView>()
    private val empty = TextView(context)
    private val footerHint = TextView(context)
    private lateinit var chipsView: View
    private lateinit var listView: LinearLayout

    private var results: List<Item> = emptyList()
    private var selected = 0
    private var filter = Filter.ALL
    private var mode = Mode.SEARCH
    /** True once you've moved with the arrow keys; then Space previews a file instead of typing. */
    private var navigating = false
    private val onIndexChanged: () -> Unit = { if (mode == Mode.SEARCH) refresh() }

    // Currency card
    private val calcBox = LinearLayout(context)
    private val calcLabel = TextView(context)
    private val calcExpr = TextView(context)
    private val calcAnswer = TextView(context)
    private val calcRecent = LinearLayout(context)

    // Cheat sheet
    private val cheatBox = LinearLayout(context)

    // File preview
    private val previewBox = LinearLayout(context)
    private val previewTitle = TextView(context)
    private val previewMeta = TextView(context)
    private val previewImage = ImageView(context)
    private val previewText = TextView(context)
    private val previewTextScroll = MaxHeightScroll(context, 0.45f)

    init {
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(searchRow())
        chipsView = chipRow()
        panel.addView(chipsView)
        panel.addView(buildCalcCard())
        panel.addView(buildCheatSheet())
        panel.addView(buildPreview())

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpi(12f), context.dpi(4f), context.dpi(12f), context.dpi(6f))
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
        panel.addView(footer())

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                navigating = false
                if (mode == Mode.CHEAT || mode == Mode.PREVIEW) setMode(Mode.SEARCH)
                if (mode == Mode.SEARCH) refresh()
            }
        })
        // On-screen keyboard "Go" key behaves like Enter.
        input.setOnEditorActionListener { _, _, _ ->
            open(selected, Launcher.Mode.NORMAL)
            true
        }
        refresh()
    }

    /** Back to a clean search, for reusing this panel next time it opens. */
    fun reset() {
        filter = Filter.ALL
        styleChips()
        navigating = false
        setMode(Mode.SEARCH)
        if (input.text.isNullOrEmpty()) refresh() else input.setText("")
    }

    // ---------- building ----------

    private fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) paint.isFakeBoldText = true
    }

    private fun searchRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dpi(22f), context.dpi(16f), context.dpi(18f), context.dpi(12f))
        }
        row.addView(ImageView(context).apply { setImageDrawable(StrokeIcon(Glyph.SEARCH, pal.sub)) },
            LinearLayout.LayoutParams(context.dpi(22f), context.dpi(22f)).apply { marginEnd = context.dpi(14f) })
        input.apply {
            background = null
            hint = SEARCH_PLACEHOLDER
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
        row.addView(label(12f, pal.sub).apply {
            text = Prefs.hotkey(context).label
            setPadding(context.dpi(9f), context.dpi(4f), context.dpi(9f), context.dpi(4f))
            background = GradientDrawable().apply { cornerRadius = context.dp(8f); setColor(pal.chip) }
        })
        return row
    }

    private fun chipRow(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dpi(22f), 0, context.dpi(22f), context.dpi(10f))
        }
        for (f in Filter.entries) {
            val c = label(13f, pal.text).apply {
                text = f.label
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
        row.addView(label(12f, pal.sub).apply { text = "Ctrl Tab to switch" })
        styleChips()
        return row
    }

    private fun footer(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dpi(22f), context.dpi(8f), context.dpi(22f), context.dpi(12f))
        addView(label(12f, pal.sub, bold = true).apply { text = "OrbitSearch" })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(footerHint.apply {
            text = SEARCH_HINT
            setTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
    }

    private fun card(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = context.dp(20f)
        setColor(if (pal.dark) 0x0FFFFFFF else 0x0A0B1220)
    }

    private fun buildCalcCard(): View {
        calcBox.apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = GONE
            setPadding(context.dpi(18f), context.dpi(2f), context.dpi(18f), context.dpi(8f))
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(context.dpi(22f), context.dpi(18f), context.dpi(22f), context.dpi(16f))
        }
        calcLabel.apply {
            setTextColor(pal.sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); letterSpacing = 0.1f
            paint.isFakeBoldText = true
        }
        calcExpr.apply { setTextColor(pal.sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setPadding(0, context.dpi(12f), 0, 0) }
        calcAnswer.apply {
            setTextColor(pal.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            fontFeatureSettings = "tnum"
            isSingleLine = true
        }
        calcRecent.orientation = LinearLayout.VERTICAL
        left.addView(calcLabel)
        left.addView(calcExpr)
        left.addView(calcAnswer)
        left.addView(calcRecent)
        calcBox.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        return calcBox
    }

    private fun buildCheatSheet(): View {
        cheatBox.apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            setPadding(context.dpi(18f), 0, context.dpi(18f), context.dpi(6f))
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dpi(4f), 0, context.dpi(4f), context.dpi(10f))
            addView(label(18f, pal.text, bold = true).apply { text = "Keyboard shortcuts" })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(label(12f, pal.sub).apply { text = "Hotkey: " + Prefs.hotkey(context).label })
        }
        cheatBox.addView(head)
        val groups = listOf(
            "OPEN" to listOf("Open highlighted (first) result" to "↵", "Open as pop-up window" to "Ctrl ↵",
                "Open side by side" to "Shift ↵", "Open result 1–8" to "Ctrl 1–8"),
            "MOVE" to listOf("Next / previous result" to "↓ ↑", "Switch filter" to "Ctrl Tab",
                "Clear search, then close" to "Esc", "OrbitSearch settings" to "Ctrl ,"),
            "TOOLS" to listOf("Area screenshot (anywhere)" to "Alt ⇧ S", "Preview a file" to "↓ then Space",
                "Focus timer" to "focus 25", "Keep screen awake" to "awake 1h"),
            "TYPE TO…" to listOf("Calculate" to "128*1.06", "Convert currency" to "100 usd to myr",
                "Volume / brightness" to "vol 40", "Device health" to "health"),
        )
        var row: LinearLayout? = null
        groups.forEachIndexed { i, (name, items) ->
            if (i % 2 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                cheatBox.addView(row)
            }
            val g = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = card()
                setPadding(context.dpi(16f), context.dpi(12f), context.dpi(16f), context.dpi(10f))
                addView(label(11f, pal.sub, bold = true).apply { text = name; letterSpacing = 0.1f; setPadding(0, 0, 0, context.dpi(4f)) })
                for ((what, key) in items) addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = context.dpi(32f)
                    addView(label(14f, pal.text).apply { text = what }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label(12f, pal.text, bold = true).apply {
                        text = key
                        setPadding(context.dpi(8f), context.dpi(3f), context.dpi(8f), context.dpi(3f))
                        background = GradientDrawable().apply { cornerRadius = context.dp(7f); setColor(pal.chip) }
                    })
                })
            }
            row?.addView(g, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(context.dpi(4f), context.dpi(4f), context.dpi(4f), context.dpi(4f))
            })
        }
        if (groups.size % 2 == 1) row?.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply {
            setMargins(context.dpi(4f), 0, context.dpi(4f), 0)
        })
        return cheatBox
    }

    private fun buildPreview(): View = previewBox.apply {
        orientation = LinearLayout.VERTICAL
        visibility = GONE
        setPadding(context.dpi(22f), 0, context.dpi(22f), context.dpi(8f))
        addView(previewTitle.apply {
            setTextColor(pal.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); paint.isFakeBoldText = true; isSingleLine = true
        })
        addView(previewMeta.apply { setTextColor(pal.sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, context.dpi(2f), 0, context.dpi(10f)) })
        addView(previewImage.apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            clipToOutline = true
            background = card()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        previewText.apply {
            setTextColor(pal.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(context.dpi(14f), context.dpi(12f), context.dpi(14f), context.dpi(12f))
            background = card()
        }
        previewTextScroll.addView(previewText)
        addView(previewTextScroll)
    }

    /** A filter chip hides when everything it would show is switched off in settings. */
    private fun filterOn(f: Filter): Boolean = when (f) {
        Filter.FILES -> com.aniq.orbit.Feature.FILES.on(context)
        Filter.SETTINGS -> com.aniq.orbit.Feature.SETTINGS_PAGES.on(context)
        else -> true
    }

    private fun styleChips() {
        if (!filterOn(filter)) filter = Filter.ALL
        chips.forEachIndexed { i, c ->
            c.visibility = if (filterOn(Filter.entries[i])) VISIBLE else GONE
            val on = Filter.entries[i] == filter
            (c.background as GradientDrawable).setColor(if (on) pal.accent else pal.chip)
            c.setTextColor(if (on) pal.onAccent else pal.text)
        }
    }

    // ---------- modes ----------

    private fun setMode(m: Mode) {
        if (mode == m && m != Mode.SEARCH) return
        mode = m
        val search = m == Mode.SEARCH
        chipsView.visibility = if (search) VISIBLE else GONE
        listView.visibility = if (search) VISIBLE else GONE
        cheatBox.visibility = if (m == Mode.CHEAT) VISIBLE else GONE
        previewBox.visibility = if (m == Mode.PREVIEW) VISIBLE else GONE
        if (!search) calcBox.visibility = GONE
        footerHint.text = when (m) {
            Mode.SEARCH -> SEARCH_HINT
            Mode.CHEAT -> "Ctrl /  or  Esc  back to search"
            Mode.PREVIEW -> "↵ Open    ↓ ↑ Next file    Space / Esc Close preview"
        }
        input.hint = SEARCH_PLACEHOLDER
        // Quick cross-fade into the new view.
        val shown = when (m) { Mode.CHEAT -> cheatBox; Mode.PREVIEW -> previewBox; else -> listView }
        shown.alpha = 0f
        shown.animate().alpha(1f).setDuration(140).setInterpolator(Motion.easeOut).start()
    }

    // ---------- search ----------

    private fun refresh() {
        val keep = navigating
        val prev = results.getOrNull(selected)
        results = SearchEngine.search(context, input.text?.toString().orEmpty(), filter) {
            if (mode == Mode.SEARCH && isAttachedToWindow) refresh()
        }
        // Enter opens the first result by default; keep your place only if you'd moved.
        selected = if (keep && prev != null) results.indexOfFirst { it.title == prev.title }.coerceAtLeast(0) else 0
        for (i in rows.indices) {
            if (i < results.size) {
                rows[i].visibility = VISIBLE
                rows[i].bind(results[i])
            } else {
                rows[i].visibility = GONE
            }
        }
        empty.visibility = if (results.isEmpty()) VISIBLE else GONE
        empty.text = if (filter == Filter.FILES && input.text.isNullOrBlank()) "Type a file name" else "No matches"
        updateCalcCard()
        paintSelection()
    }

    private fun updateCalcCard() {
        // Maths answers show as a normal result row; only currency gets the big card.
        val top = results.firstOrNull()
        val money = (top as? Item.Money)?.takeIf { it.value != null }
        if (mode != Mode.SEARCH || money == null) {
            calcBox.visibility = GONE
            return
        }
        if (calcBox.visibility != VISIBLE) {
            calcBox.visibility = VISIBLE
            calcBox.alpha = 0f
            calcBox.translationY = -context.dp(6f)
            calcBox.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(Motion.easeOut).start()
        }
        calcLabel.text = "CURRENCY"
        calcExpr.text = "${money.q.amount.toString().removeSuffix(".0")} ${money.q.from} → ${money.q.to}"
        calcAnswer.text = money.title
        calcRecent.removeAllViews()
        val recent = CalcHistory.list(context).take(3)
        if (recent.isNotEmpty()) {
            calcRecent.setPadding(0, context.dpi(10f), 0, 0)
            calcRecent.addView(label(11f, pal.sub, bold = true).apply { text = "RECENT"; letterSpacing = 0.1f })
            for ((e, v) in recent) calcRecent.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, context.dpi(3f), 0, 0)
                addView(label(14f, pal.sub).apply { text = e; isSingleLine = true }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(14f, pal.sub).apply { text = v })
            })
        }
    }

    private fun pretty(expr: String) = expr.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ")
        .replace(Regex("(?<=[\\d)])-"), " − ").replace(Regex("\\s+"), " ").trim()

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
        navigating = false
        refresh()
    }

    private fun open(i: Int, mode: Launcher.Mode) {
        val item = results.getOrNull(i) ?: return
        if (item is Item.Money && item.value == null) return
        if (Launcher.open(context, item, mode)) host.dismiss()
    }

    // ---------- file preview ----------

    private fun showPreview() {
        val f = results.getOrNull(selected) as? Item.File ?: return
        setMode(Mode.PREVIEW)
        previewTitle.text = f.entry.name
        previewMeta.text = f.entry.describe(context)
        previewImage.setImageDrawable(null)
        previewImage.visibility = GONE
        previewText.text = "Loading preview…"
        previewTextScroll.visibility = VISIBLE
        val w = (panel.width - context.dpi(44f)).coerceAtLeast(context.dpi(300f))
        val h = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        val want = f.entry.id
        FilePreview.load(context, f.entry, w, h) { content ->
            val now = results.getOrNull(selected) as? Item.File
            if (mode != Mode.PREVIEW || now?.entry?.id != want) return@load
            when {
                content.bitmap != null -> {
                    previewImage.setImageBitmap(content.bitmap)
                    previewImage.visibility = VISIBLE
                    previewTextScroll.visibility = GONE
                }
                content.text != null -> previewText.text = content.text
                else -> previewText.text = "No preview for this kind of file. Press Enter to open it."
            }
        }
    }

    private fun previewStep(dir: Int) {
        var i = selected
        do { i += dir } while (i in results.indices && results[i] !is Item.File)
        if (i !in results.indices) return
        selected = i
        paintSelection()
        showPreview()
    }

    // ---------- keys ----------

    private fun openSettings() {
        if (Launcher.open(context, Item.OrbitSettings, Launcher.Mode.NORMAL)) host.dismiss()
    }

    private fun previewKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_SPACE -> { setMode(Mode.SEARCH); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { open(selected, Launcher.Mode.NORMAL); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { previewStep(1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { previewStep(-1); return true }
        }
        return false
    }

    private fun cheatKey(e: KeyEvent): Boolean {
        val close = e.keyCode == KeyEvent.KEYCODE_ESCAPE || e.keyCode == KeyEvent.KEYCODE_BACK ||
            (e.keyCode == KeyEvent.KEYCODE_SLASH && e.isCtrlPressed)
        if (close) { setMode(Mode.SEARCH); return true }
        return false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (mode) {
            Mode.PREVIEW -> return previewKey(event) || super.dispatchKeyEvent(event)
            Mode.CHEAT -> return cheatKey(event) || super.dispatchKeyEvent(event)
            Mode.SEARCH -> {}
        }
        val ctrl = event.isCtrlPressed
        when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
                // Like Raycast: first Esc clears what you typed, the next one closes.
                if (!input.text.isNullOrEmpty() && event.keyCode == KeyEvent.KEYCODE_ESCAPE) input.setText("") else host.dismiss()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (results.isNotEmpty()) { navigating = true; select((selected + 1) % results.size) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (results.isNotEmpty()) { navigating = true; select((selected - 1 + results.size) % results.size) }
                return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                val isFile = results.getOrNull(selected) is Item.File
                if (isFile && (navigating || event.isShiftPressed)) { showPreview(); return true }
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val m = when {
                    ctrl -> Launcher.Mode.POPUP
                    event.isShiftPressed -> Launcher.Mode.SIDE_BY_SIDE
                    else -> Launcher.Mode.NORMAL
                }
                open(selected, m)
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                if (ctrl) {
                    val shown = Filter.entries.filter { filterOn(it) }
                    val n = shown.size
                    val step = if (event.isShiftPressed) n - 1 else 1
                    setFilter(shown[(shown.indexOf(filter).coerceAtLeast(0) + step) % n])
                }
                return true
            }
            KeyEvent.KEYCODE_SLASH -> if (ctrl) { setMode(Mode.CHEAT); return true }
            KeyEvent.KEYCODE_DEL -> if (ctrl) { input.setText(""); return true }
            KeyEvent.KEYCODE_COMMA -> if (ctrl) { openSettings(); return true }
            KeyEvent.KEYCODE_C -> if (ctrl && input.selectionStart == input.selectionEnd) {
                val top = results.getOrNull(selected)
                if (top is Item.Calc || top is Item.Money) { open(selected, Launcher.Mode.NORMAL); return true }
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

    // ---------- motion ----------
    // Only alpha, scale and translation are animated: these run on the GPU's render
    // properties, so every frame is cheap and can hit 120 Hz. The window also asks the
    // display for its highest refresh rate (see Motion.kt).

    private var closing = false

    private fun animateIn() {
        closing = false
        val rise = context.dp(14f)
        panel.animate().cancel()
        panel.alpha = 0f
        panel.scaleX = 0.94f
        panel.scaleY = 0.94f
        panel.translationY = -rise
        panel.pivotY = 0f
        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(0)
            .setDuration(Motion.ENTER_MS)
            .setInterpolator(Motion.springOut)
            .withLayer()
            .start()
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

    /** Called once the first frame is on screen, for the open-time measurement. */
    var onFirstFrame: (() -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AppIndex.addListener(onIndexChanged)
        animateIn()
        onFirstFrame?.let { cb -> post { cb() } }
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

    companion object {
        private const val SEARCH_PLACEHOLDER = "Search apps, files, settings, maths…"
        private const val SEARCH_HINT = "↵ Open    Ctrl / Shortcuts    Esc Close"
    }
}

/** Scrolls when content gets long, so the panel never grows past a share of the screen. */
class MaxHeightScroll(context: Context, private val share: Float) : android.widget.ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * share).toInt()
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
    }
}
