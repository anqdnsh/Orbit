package com.aniq.orbit

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.aniq.orbit.ui.Palette
import com.aniq.orbit.ui.dp
import com.aniq.orbit.ui.dpi

/** Hotkey, feature switches, appearance, permissions, shortcuts and about. Built in code to keep the app free of extra libraries. */
class SettingsActivity : Activity() {

    private lateinit var pal: Palette
    private lateinit var status: TextView
    private lateinit var perf: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        pal = Palette.current(this)
        setTheme(
            if (pal.dark) android.R.style.Theme_DeviceDefault_NoActionBar
            else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(pal.pageBg)
        val light = if (pal.dark) 0 else
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(
            light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpi(28f)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(col)
        }
        val pad = dpi(28f)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            col.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            insets
        }
        // Keep the page a comfortable reading width on a 14.6" screen.
        val wrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(scroll, LinearLayout.LayoutParams(minOf(dpi(760f), resources.displayMetrics.widthPixels), LinearLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(wrap)

        col.addView(text("OrbitSearch", 32f, pal.text, bold = true))
        col.addView(text("Fast search for your Galaxy Tab. Press the hotkey anywhere, type, press Enter.", 15f, pal.sub).also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dpi(8f)
        })
        val ctx = this

        // ---------- 1. Hotkey ----------
        col.addView(section("Hotkey"))
        col.addView(card {
            status = text("", 15f, pal.sub, bold = true)
            addView(status)
            addView(text(
                "Switch on “${getString(R.string.a11y_label)}” in Accessibility to open OrbitSearch from any app.",
                14f, pal.sub
            ))
            addView(button("Open Accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(text(
                "Switch greyed out? Android blocks this for sideloaded apps: open app info, tap ⋮, " +
                    "Allow restricted settings, then try again.",
                12f, pal.sub
            ).also { pad(it, top = 10f) })
            addView(linkButton("Open OrbitSearch app info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            addView(divider())
            addView(text("Shortcut", 15f, pal.text, bold = true))
            addView(radios(
                Hotkey.entries.map { it.label },
                Hotkey.entries.indexOf(Prefs.hotkey(ctx))
            ) { i -> Prefs.setHotkey(ctx, Hotkey.entries[i]); recreate() })
            addView(divider())
            addView(text("Other ways to open", 15f, pal.text, bold = true))
            addView(text(
                "• Tap the OrbitSearch icon in the taskbar or app drawer.\n" +
                    "• Add the OrbitSearch tile to Quick Settings.\n" +
                    "• On Android 16+, give OrbitSearch its own shortcut in physical keyboard settings, if One UI shows it.",
                14f, pal.sub
            ))
            addView(linkButton("Open physical keyboard settings") {
                val i = Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS)
                startActivity(if (i.resolveActivity(packageManager) != null) i else Intent(Settings.ACTION_SETTINGS))
            })
        })

        // ---------- 2. Features ----------
        col.addView(section("Features"))
        col.addView(text("Switch off anything you don't use. It disappears from results and does no work.", 13f, pal.sub).also {
            pad(it, top = 0f); (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dpi(10f)
        })
        for (g in Feature.Group.entries) col.addView(card {
            addView(text(g.title, 15f, pal.text, bold = true))
            Feature.entries.filter { it.group == g }.forEachIndexed { i, f ->
                if (i > 0) addView(divider(thin = true))
                addView(toggleRow(f.label, f.detail, f.on(ctx)) { v ->
                    f.set(ctx, v)
                    if (f == Feature.AREA || f == Feature.TIMERS || f == Feature.FILES) shortcutsDirty = true
                })
            }
        })

        // ---------- 3. Appearance ----------
        col.addView(section("Appearance"))
        col.addView(card {
            addView(text("Theme", 15f, pal.text, bold = true))
            val ids = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)
            addView(radios(
                listOf("Match system", "Light", "Dark"),
                ids.indexOf(Prefs.theme(ctx)).coerceAtLeast(0)
            ) { i ->
                Prefs.setTheme(ctx, ids[i])
                recreate()
            })
            addView(divider())
            addView(toggleRow(
                "Frosted glass",
                "Blur behind the panel. Off gives a solid panel and saves battery and GPU",
                !Palette.reduced(ctx),
            ) { v -> Prefs.of(ctx).edit().putBoolean("reduce_effects", !v).apply() })
        })

        // ---------- 4. Permissions ----------
        col.addView(section("Permissions"))
        col.addView(card {
            addView(text("All optional. Each is only used for the feature next to it.", 13f, pal.sub))
            permFiles = permRow("All files access", "File search and preview") {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
            addView(permFiles)
            addView(divider(thin = true))
            permWrite = permRow("Modify system settings", "“brightness 70” and auto-rotate run straight away") {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
            addView(permWrite)
            addView(divider(thin = true))
            permDnd = permRow("Do Not Disturb access", "“dnd”, and focus timers that silence alerts") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            addView(permDnd)
            addView(text(
                "Restart and Shut down open Samsung's power menu, since only the system can power off the tablet.",
                12f, pal.sub
            ).also { pad(it, top = 12f) })
        })

        // ---------- 5. Keyboard shortcuts ----------
        col.addView(section("Keyboard shortcuts"))
        shortcutsCard = card { }
        col.addView(shortcutsCard)
        fillShortcuts()

        // ---------- 6. About ----------
        col.addView(section("About"))
        col.addView(card {
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null }
            addView(text("OrbitSearch ${ver ?: ""}".trim(), 15f, pal.text, bold = true))
            perf = text("", 13f, pal.sub)
            addView(perf)
            addView(text(
                "Keeps a panel ready and reuses it, does no background work, and gives memory back when Android asks.",
                12f, pal.sub
            ))
            addView(linkButton("Show welcome guide") {
                startActivity(Intent(ctx, GuideActivity::class.java))
            })
        })
    }

    private var shortcutsDirty = false
    private lateinit var shortcutsCard: LinearLayout
    private lateinit var permFiles: LinearLayout
    private lateinit var permWrite: LinearLayout
    private lateinit var permDnd: LinearLayout

    private fun fillShortcuts() {
        shortcutsCard.removeAllViews()
        val keys = buildList {
            add(Prefs.hotkey(this@SettingsActivity).label to "Open / close OrbitSearch")
            add("Enter" to "Open the highlighted result (the first one, unless you move)")
            add("↑  ↓" to "Move through results")
            add("Ctrl + Enter" to "Open as a pop-up window")
            add("Shift + Enter" to "Open side by side")
            add("Ctrl + 1–8" to "Open that result directly")
            add("Ctrl + Tab" to "Switch filter")
            add("Ctrl + /" to "Show all shortcuts inside OrbitSearch")
            if (Feature.AREA.on(this@SettingsActivity)) add("Alt + Shift + S" to "Area screenshot from anywhere (saved and copied)")
            if (Feature.FILES.on(this@SettingsActivity)) add("↓ then Space" to "Preview the highlighted file")
            if (Feature.TIMERS.on(this@SettingsActivity)) add("Space (on the timer)" to "Pause / resume the timer")
            add("Ctrl + C" to "Copy a calculator or currency answer")
            add("Ctrl + Backspace" to "Clear the search")
            add("Ctrl + ," to "OrbitSearch settings")
            add("Esc" to "Clear the search, press again to close")
        }
        for ((k, v) in keys) shortcutsCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(7f), 0, dpi(7f))
            addView(text(v, 14f, pal.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(keycap(k), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        })
    }

    override fun onResume() {
        super.onResume()
        val on = Prefs.isHotkeyServiceOn(this)
        status.text = if (on) "● On: ${Prefs.hotkey(this).label} opens OrbitSearch from any app"
        else "○ Off: the hotkey isn't switched on yet"
        status.setTextColor(if (on) pal.accent else pal.text)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        setPerm(permFiles, android.os.Environment.isExternalStorageManager())
        setPerm(permWrite, Settings.System.canWrite(this))
        setPerm(permDnd, nm.isNotificationPolicyAccessGranted)
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576.0
        val pssMb = android.os.Debug.getPss() / 1024.0
        perf.text = buildString {
            append(if (Stats.opens > 0) "Panel open time: last ${Stats.lastOpenMs} ms, average %.0f ms (${Stats.opens} opens)\n".format(Stats.avgOpenMs)
                   else "Panel open time: open OrbitSearch with the hotkey to measure\n")
            append("Last search: %.2f ms\n".format(com.aniq.orbit.search.SearchEngine.lastSearchMicros / 1000.0))
            append("Memory: %.0f MB total, %.1f MB app data\n".format(pssMb, heapMb))
            append("Apps indexed: ${com.aniq.orbit.search.AppIndex.apps.size}")
        }
    }

    // ---------- rows ----------

    private fun section(title: String) = text(title.uppercase(), 12f, pal.sub, bold = true).apply {
        letterSpacing = 0.1f
        (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dpi(18f); bottomMargin = dpi(8f); marginStart = dpi(6f) }
    }

    private fun divider(thin: Boolean = false) = View(this).apply {
        setBackgroundColor(pal.divider)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(1f)).apply {
            val m = if (thin) 4f else 14f
            topMargin = dpi(m); bottomMargin = dpi(m)
        }
    }

    /** Title + detail on the left, a switch on the right; the whole row toggles. */
    private fun toggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(56f)
            setPadding(0, dpi(6f), 0, dpi(6f))
            val texts = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15f, pal.text).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dpi(2f) })
                addView(text(detail, 13f, pal.sub).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0 })
            }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12f) })
            val sw = Switch(this@SettingsActivity).apply {
                isChecked = checked
                contentDescription = title
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                thumbTintList = ColorStateList(states, intArrayOf(pal.onAccent, if (pal.dark) 0xFFBDBDC2.toInt() else 0xFFFFFFFF.toInt()))
                trackTintList = ColorStateList(states, intArrayOf(pal.accent, if (pal.dark) 0xFF3A3A3F.toInt() else 0xFFC9C9CF.toInt()))
                setOnCheckedChangeListener { _, v ->
                    onChange(v)
                    if (shortcutsDirty) { shortcutsDirty = false; fillShortcuts() }
                }
            }
            addView(sw)
            isClickable = true
            setOnClickListener { sw.toggle() }
        }

    /** A permission with its live state: "Allowed" or an Allow button. */
    private fun permRow(title: String, detail: String, onAllow: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpi(56f)
        val texts = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, pal.text).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dpi(2f) })
            addView(text(detail, 13f, pal.sub).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0 })
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12f) })
        addView(text("", 13f, pal.sub, bold = true).apply {
            tag = "state"
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(button("Allow") { onAllow() }.apply {
            tag = "allow"
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
        })
    }

    private fun setPerm(row: LinearLayout, granted: Boolean) {
        val state = row.findViewWithTag<TextView>("state")
        val allow = row.findViewWithTag<View>("allow")
        state.text = if (granted) "✓ Allowed" else ""
        state.setTextColor(pal.accent)
        state.visibility = if (granted) View.VISIBLE else View.GONE
        allow.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun keycap(k: String) = text(k, 13f, pal.text, bold = true).apply {
        setPadding(dpi(10f), dpi(4f), dpi(10f), dpi(4f))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        background = GradientDrawable().apply {
            cornerRadius = dp(8f)
            setColor(pal.pageBg)
            setStroke(dpi(1f), pal.divider)
        }
    }

    /** Quieter secondary button: accent text, no fill. */
    private fun linkButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(pal.accent)
        minHeight = dpi(44f)
        minimumHeight = dpi(44f)
        setPadding(0, 0, 0, 0)
        background = null
        stateListAnimator = null
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    // ---------- small builders ----------

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) paint.isFakeBoldText = true
        setLineSpacing(0f, 1.15f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpi(6f) }
    }

    private fun pad(v: View, top: Float) {
        (v.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dpi(top)
    }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = dpi(20f)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            cornerRadius = dp(22f)
            setColor(pal.card)
            setStroke(dpi(1f), pal.divider)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpi(16f) }
        build()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(pal.onAccent)
        minHeight = dpi(48f)
        background = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(pal.accent)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpi(10f) }
    }

    private fun radios(labels: List<String>, checked: Int, onPick: (Int) -> Unit) = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        labels.forEachIndexed { i, l ->
            addView(RadioButton(this@SettingsActivity).apply {
                id = View.generateViewId()
                text = l
                setTextColor(pal.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minHeight = dpi(48f)
                isChecked = i == checked
                setOnClickListener { onPick(i) }
            })
        }
    }
}
