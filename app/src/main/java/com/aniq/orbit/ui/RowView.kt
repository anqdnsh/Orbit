package com.aniq.orbit.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aniq.orbit.search.IconLoader
import com.aniq.orbit.search.Item

/** One result line. A fixed pool of these is reused, so typing never inflates new views. */
class RowView(context: Context, private val pal: Palette) : LinearLayout(context) {
    private val bg = GradientDrawable().apply { cornerRadius = context.dp(14f) }
    private val tileBg = GradientDrawable().apply { cornerRadius = context.dp(10f) }
    private val icon = ImageView(context)
    private val title = TextView(context)
    private val sub = TextView(context)
    private val key = TextView(context)
    private var item: Item? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpi(56f)
        val h = context.dpi(12f)
        setPadding(h, context.dpi(6f), h, context.dpi(6f))
        background = bg
        isClickable = true
        isFocusable = false

        val s = context.dpi(38f)
        addView(icon, LayoutParams(s, s).apply { marginEnd = context.dpi(14f) })

        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        sub.isSingleLine = true
        sub.ellipsize = TextUtils.TruncateAt.END
        texts.addView(title)
        texts.addView(sub)
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        key.setPadding(context.dpi(12f), 0, 0, 0)
        addView(key)
    }

    fun bind(it: Item) {
        item = it
        title.text = it.title
        sub.text = it.subtitle
        if (it is Item.App) {
            icon.background = null
            icon.setPadding(0, 0, 0, 0)
            IconLoader.load(it.app, icon)
        } else {
            icon.tag = null
            icon.background = tileBg
            val p = context.dpi(8f)
            icon.setPadding(p, p, p, p)
        }
    }

    fun setSelected(selected: Boolean, index: Int) {
        val it = item ?: return
        val fg = if (selected) pal.onSelection else pal.text
        val fgSub = if (selected) pal.onSelectionSub else pal.sub
        bg.setColor(if (selected) pal.selection else 0x00000000)
        title.setTextColor(fg)
        sub.setTextColor(fgSub)
        key.setTextColor(fgSub)
        key.text = when {
            selected -> "↵"
            index < 9 -> "Ctrl ${index + 1}"
            else -> ""
        }
        if (it !is Item.App) {
            tileBg.setColor(if (selected) 0x29FFFFFF else pal.tile)
            icon.setImageDrawable(StrokeIcon(it.glyph, fg))
        }
        contentDescription = "${it.title}, ${it.subtitle}"
    }
}
