package com.aniq.orbit

import android.app.Activity
import android.content.Intent
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
import android.widget.TextView
import com.aniq.orbit.ai.AiProvider
import com.aniq.orbit.ai.AiSettings
import com.aniq.orbit.ai.KeyVault
import com.aniq.orbit.ui.Palette
import com.aniq.orbit.ui.dp
import com.aniq.orbit.ui.dpi

/** Theme, hotkey set-up and the keyboard map. Built in code to keep the app free of extra libraries. */
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
            (it.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dpi(20f)
        })

        // Hotkey
        col.addView(card {
            addView(text("Keyboard hotkey", 18f, pal.text, bold = true))
            status = text("", 14f, pal.sub)
            addView(status)
            addView(text(
                "To open OrbitSearch from any app, switch on “${getString(R.string.a11y_label)}” in Accessibility. " +
                    "It only watches for the hotkey and never reads your screen.\n\n" +
                    "If the switch is greyed out (Android blocks this for sideloaded apps): " +
                    "Settings › Apps › OrbitSearch › ⋮ menu › Allow restricted settings, then try again.",
                14f, pal.sub
            ))
            addView(button("Open Accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(button("Open OrbitSearch app info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            addView(text("Shortcut", 15f, pal.text, bold = true).also { pad(it, top = 16f) })
            addView(radios(
                Hotkey.entries.map { it.label },
                Hotkey.entries.indexOf(Prefs.hotkey(this@SettingsActivity))
            ) { i -> Prefs.setHotkey(this@SettingsActivity, Hotkey.entries[i]) })
        })

        // Theme
        col.addView(card {
            addView(text("Appearance", 18f, pal.text, bold = true))
            val ids = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)
            addView(radios(
                listOf("Match system", "Light", "Dark"),
                ids.indexOf(Prefs.theme(this@SettingsActivity)).coerceAtLeast(0)
            ) { i ->
                Prefs.setTheme(this@SettingsActivity, ids[i])
                recreate()
            })
            addView(text("Reduce effects", 15f, pal.text, bold = true).also { pad(it, top = 12f) })
            addView(radios(
                listOf("Frosted glass (blur behind the panel)", "Solid panel, no blur (saves battery and GPU)"),
                if (Palette.reduced(this@SettingsActivity)) 1 else 0,
            ) { i -> Prefs.of(this@SettingsActivity).edit().putBoolean("reduce_effects", i == 1).apply() })
            addView(button("Show welcome guide") {
                startActivity(Intent(this@SettingsActivity, GuideActivity::class.java))
            })
        })

        // Performance
        col.addView(card {
            addView(text("Performance", 18f, pal.text, bold = true))
            perf = text("", 14f, pal.sub)
            addView(perf)
            addView(text(
                "OrbitSearch keeps a panel ready and reuses it, does no background work, and gives memory back when " +
                    "Android asks. Numbers update as you use it.",
                12f, pal.sub
            ))
        })

        // System controls
        col.addView(card {
            addView(text("System controls", 18f, pal.text, bold = true))
            addView(text(
                "Type commands like “restart”, “lock”, “screenshot”, “torch”, “volume 40”, “brightness 70”, " +
                    "“dnd” or “wifi”. Each result says what Enter will do: Runs now, Opens Settings, " +
                    "or Asks permission once.\n\n" +
                    "Restart and Shut down open Samsung's power menu. Android only lets the system power off " +
                    "or restart the tablet, so you tap the final button.\n\n" +
                    "Optional permissions for running these straight away:",
                14f, pal.sub
            ))
            addView(button("Allow brightness and auto-rotate") {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            })
            addView(button("Allow Do Not Disturb control") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            })
        })

        // Quick AI
        col.addView(card {
            val ctx = this@SettingsActivity
            val current = AiSettings.provider(ctx)
            addView(text("Quick AI", 18f, pal.text, bold = true))
            addView(text(
                "Type anything, press Tab, and the answer appears right in OrbitSearch. Use your own API key. " +
                    "It's encrypted on this tablet and only sent to the provider you choose.",
                14f, pal.sub
            ))
            addView(text("Provider", 15f, pal.text, bold = true).also { pad(it, top = 12f) })
            addView(radios(AiProvider.entries.map { it.label }, AiProvider.entries.indexOf(current)) { i ->
                AiSettings.setProvider(ctx, AiProvider.entries[i])
                recreate()
            })

            addView(text("API key", 15f, pal.text, bold = true).also { pad(it, top = 12f) })
            val keyField = field(
                KeyVault.hint(ctx, current)?.let { "Saved ($it). Paste a new key to replace it" }
                    ?: "Paste your key (get one at ${current.keyUrl})",
                password = true,
            )
            addView(keyField)

            addView(text("Model", 15f, pal.text, bold = true).also { pad(it, top = 12f) })
            val modelField = field(current.defaultModel.ifBlank { "e.g. llama3.1" }).apply {
                setText(AiSettings.model(ctx, current))
            }
            addView(modelField)
            addView(text("Model names change often. If you get a “not found” error, copy the current name from your provider's site.", 12f, pal.sub))

            var urlField: android.widget.EditText? = null
            if (current == AiProvider.CUSTOM) {
                addView(text("Server address", 15f, pal.text, bold = true).also { pad(it, top = 12f) })
                urlField = field("e.g. http://192.168.1.20:11434/v1 (Ollama on your PC)").apply {
                    setText(AiSettings.customUrl(ctx))
                }
                addView(urlField)
            }

            addView(button("Save") {
                val k = keyField.text.toString().trim()
                if (k.isNotEmpty()) KeyVault.save(ctx, current, k)
                AiSettings.setModel(ctx, current, modelField.text.toString())
                urlField?.let { AiSettings.setCustomUrl(ctx, it.text.toString()) }
                android.widget.Toast.makeText(ctx, "Saved", android.widget.Toast.LENGTH_SHORT).show()
                recreate()
            })
            if (KeyVault.has(ctx, current)) addView(button("Remove saved key") {
                KeyVault.remove(ctx, current)
                recreate()
            })
        })

        // Other ways to open
        col.addView(card {
            addView(text("Other ways to open", 18f, pal.text, bold = true))
            addView(text(
                "• Tap the OrbitSearch icon in the taskbar or app drawer.\n" +
                    "• Add the OrbitSearch tile to Quick Settings (edit the quick panel).\n" +
                    "• Android 16 and later can give any app its own keyboard shortcut in the " +
                    "physical keyboard settings, if your One UI version shows that option. " +
                    "Point it at OrbitSearch and it opens without the accessibility switch.",
                14f, pal.sub
            ))
            addView(button("Open physical keyboard settings") {
                val i = Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS)
                startActivity(if (i.resolveActivity(packageManager) != null) i else Intent(Settings.ACTION_SETTINGS))
            })
        })

        // Keyboard map
        col.addView(card {
            addView(text("Keyboard map", 18f, pal.text, bold = true))
            val keys = listOf(
                Prefs.hotkey(this@SettingsActivity).label to "Open / close OrbitSearch",
                "Enter" to "Open the highlighted result (the first one, unless you move)",
                "↑  ↓" to "Move through results",
                "Ctrl + Enter" to "Open as a pop-up window",
                "Shift + Enter" to "Open side by side",
                "Ctrl + 1–8" to "Open that result directly",
                "Tab" to "Quick AI: answer what you typed, right in the panel",
                "Enter / Tab (in Quick AI)" to "Ask a follow-up",
                "Ctrl + N (in Quick AI)" to "Start a new question",
                "Esc (in Quick AI)" to "Back to search",
                "Ctrl + Tab" to "Switch filter: All, Apps, Files, System, Settings",
                "Ctrl + /" to "Show all shortcuts inside OrbitSearch",
                "Alt + Shift + S" to "Area screenshot from anywhere (saved and copied)",
                "↓ then Space  /  Shift + Space" to "Preview the highlighted file",
                "Space (on the timer)" to "Pause / resume the focus timer",
                "Ctrl + C" to "Copy a calculator, currency or AI answer",
                "Ctrl + Backspace" to "Clear the search",
                "Ctrl + ," to "OrbitSearch settings",
                "Esc" to "Clear the search, press again to close",
            )
            for ((k, v) in keys) addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpi(8f), 0, dpi(8f))
                addView(text(v, 14f, pal.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(k, 13f, pal.text, bold = true).apply {
                    setPadding(dpi(10f), dpi(4f), dpi(10f), dpi(4f))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8f)
                        setColor(pal.pageBg)
                        setStroke(dpi(1f), pal.divider)
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            })
        })
    }

    override fun onResume() {
        super.onResume()
        val on = Prefs.isHotkeyServiceOn(this)
        status.text = if (on) "● On: ${Prefs.hotkey(this).label} opens OrbitSearch from any app"
        else "○ Off: switch on the OrbitSearch hotkey in Accessibility"
        status.setTextColor(if (on) pal.accent else pal.sub)
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

    private fun field(hintText: String, password: Boolean = false) = android.widget.EditText(this).apply {
        hint = hintText
        setHintTextColor(pal.sub)
        setTextColor(pal.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isSingleLine = true
        inputType = if (password)
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minHeight = dpi(48f)
        setPadding(dpi(14f), 0, dpi(14f), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(12f)
            setColor(pal.pageBg)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpi(6f) }
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
