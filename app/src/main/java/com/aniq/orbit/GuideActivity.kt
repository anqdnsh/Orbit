package com.aniq.orbit

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aniq.orbit.search.Glyph
import com.aniq.orbit.ui.GlassPanel
import com.aniq.orbit.ui.Motion
import com.aniq.orbit.ui.Palette
import com.aniq.orbit.ui.StrokeIcon
import com.aniq.orbit.ui.dp
import com.aniq.orbit.ui.dpi

/**
 * Welcome guide on first launch (replay it from ORBIT settings). Six steps, keyboard friendly:
 * Enter = next, Esc = skip. Permission steps show live status that updates when you come back.
 */
class GuideActivity : Activity() {

    companion object {
        /** Set while step 3 is waiting, so the hotkey service can tell us it worked. */
        @Volatile var onHotkey: (() -> Unit)? = null
        fun done(ctx: android.content.Context) = Prefs.of(ctx).getBoolean("guide_done", false)
        private var resumeAt = 0
        private const val STEPS = 5
    }

    private lateinit var pal: Palette
    private var step = 0
    private lateinit var body: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var stepLabel: TextView
    private lateinit var primary: TextView
    private lateinit var back: TextView
    private lateinit var skip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        pal = Palette.current(this)
        setTheme(if (pal.dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getInt("step") ?: resumeAt
        window.decorView.setBackgroundColor(if (pal.dark) 0xFF0F0F10.toInt() else pal.pageBg)
        val light = if (pal.dark) 0 else
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(
            light, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )

        val root = FrameLayout(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val center = FrameLayout(this)
        scroll.addView(center)
        root.addView(scroll)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val card = GlassPanel(this, pal, opaque = true).apply {
            setPadding(dpi(48f), dpi(36f), dpi(48f), dpi(28f))
        }
        center.addView(card, FrameLayout.LayoutParams(
            minOf(dpi(780f), resources.displayMetrics.widthPixels - dpi(32f)), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ).apply { setMargins(0, dpi(24f), 0, dpi(24f)) })

        // Header: ORBIT · STEP n OF 6 ....... dots
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageDrawable(StrokeIcon(Glyph.ORBIT, pal.accent)) },
            LinearLayout.LayoutParams(dpi(18f), dpi(18f)).apply { marginEnd = dpi(10f) })
        stepLabel = text(12f, pal.sub, bold = true).apply { letterSpacing = 0.08f }
        head.addView(stepLabel)
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(dots)
        card.addView(head)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(26f); bottomMargin = dpi(26f)
        })

        // Footer: Esc Skip ........ Back  [Next ↵]
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        skip = text(13f, pal.sub).apply { text = "Esc  Skip guide"; setOnClickListener { finishGuide() } }
        foot.addView(skip)
        foot.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        back = text(15f, pal.sub, bold = true).apply {
            text = "Back"
            gravity = Gravity.CENTER
            setPadding(dpi(18f), 0, dpi(18f), 0)
            setOnClickListener { go(-1) }
        }
        foot.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(48f)))
        primary = text(15f, pal.onAccent, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dpi(22f), 0, dpi(22f), 0)
            background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(pal.accent) }
            setOnClickListener { next() }
        }
        foot.addView(primary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(48f)))
        card.addView(foot)

        setContentView(root)
        render(0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
    }

    override fun onResume() {
        super.onResume()
        if (step == 1 || step == 2 || step == 5) render(0) // live status after visiting Settings
    }

    override fun onDestroy() {
        onHotkey = null
        super.onDestroy()
    }

    // ---------- navigation ----------

    private fun go(dir: Int) {
        val to = (step + dir).coerceIn(0, STEPS - 1)
        if (to == step) return
        step = to
        resumeAt = step
        render(dir)
    }

    private fun next() {
        when (step) {
            STEPS - 1 -> { finishGuide(); return }
        }
        go(1)
    }

    private fun finishGuide() {
        Prefs.of(this).edit().putBoolean("guide_done", true).apply()
        resumeAt = 0
        onHotkey = null
        startActivity(Intent(this, SearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { next(); return true }
                KeyEvent.KEYCODE_ESCAPE -> { finishGuide(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (currentFocus !is EditText) { go(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (currentFocus !is EditText) { next(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- rendering ----------

    private fun render(dir: Int) {
        stepLabel.text = "OrbitSearch  ·  Step ${step + 1} of $STEPS"
        dots.removeAllViews()
        for (i in 0 until STEPS) dots.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4f)
                setColor(if (i == step) pal.accent else if (pal.dark) 0x40FFFFFF else 0x330B1220)
            }
        }, LinearLayout.LayoutParams(if (i == step) dpi(22f) else dpi(7f), dpi(7f)).apply { marginStart = dpi(6f) })
        back.visibility = if (step == 0) View.GONE else View.VISIBLE
        primary.text = when (step) {
            0 -> "Get started   ↵"
            STEPS - 1 -> "Done   ↵"
            else -> "Next   ↵"
        }
        onHotkey = null
        body.removeAllViews()
        when (step) {
            0 -> welcome()
            1 -> hotkey()
            2 -> tryIt()
            3 -> look()
            else -> extras()
        }
        if (dir != 0) {
            body.alpha = 0f
            body.translationX = dp(28f) * dir
            body.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(Motion.springOut).start()
        }
    }

    private fun title(t: String, size: Float = 34f) = body.addView(text(size, pal.text, bold = true).apply {
        text = t
        letterSpacing = -0.01f
    })

    private fun para(t: String) = body.addView(text(16f, pal.sub).apply {
        text = t
        setLineSpacing(0f, 1.3f)
        setPadding(0, dpi(10f), 0, 0)
    })

    private fun welcome() {
        body.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(pal.chip) }
            addView(ImageView(this@GuideActivity).apply { setImageDrawable(StrokeIcon(Glyph.ORBIT, pal.accent)) },
                FrameLayout.LayoutParams(dpi(40f), dpi(40f), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dpi(72f), dpi(72f)).apply { bottomMargin = dpi(18f) })
        title("Welcome to OrbitSearch", 40f)
        para("Open any app, file, setting or command in a keystroke. Press the hotkey from anywhere, type a few letters, press Enter.")
        body.addView(keyRow(listOf("Alt", "+", "Space", "→", "type", "→", "↵")), spaced(22f))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((t, d) in listOf(
            "Apps, files & settings" to "Fuzzy search that learns what you use. Space previews files.",
            "System & tools" to "Restart, lock, area screenshot, focus timer, keep awake.",
            "Maths & currency" to "Type sums like 128*1.06 or 100 usd to myr for instant answers.",
        )) grid.addView(infoCard(t, d), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dpi(10f)
        })
        body.addView(grid, spaced(16f))
    }

    private fun hotkey() {
        val on = Prefs.isHotkeyServiceOn(this)
        title("Turn on the hotkey")
        para("OrbitSearch uses Android's accessibility switch only to notice its shortcuts (Alt + Space, Alt + Shift + S). It never reads your screen.")
        body.addView(statusRow(
            on, if (on) "OrbitSearch keyboard hotkey is on" else "OrbitSearch keyboard hotkey is off",
            "Updates by itself when you come back",
            if (on) null else "Open Accessibility settings",
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, spaced(22f))
        if (!on) {
            val help = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(if (pal.dark) 0x08FFFFFF else 0x080B1220) }
                setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(16f))
                addView(text(14f, pal.text, bold = true).apply { text = "Switch greyed out? Android blocks this for sideloaded apps:" })
                for (l in listOf("1.  Settings › Apps › OrbitSearch", "2.  ⋮ menu (top right) › Allow restricted settings", "3.  Come back here and switch it on"))
                    addView(text(14f, pal.sub).apply { text = l; setPadding(0, dpi(6f), 0, 0) })
                addView(smallButton("Open OrbitSearch app info") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }, spaced(10f))
            }
            body.addView(help, spaced(12f))
        }
    }

    private fun tryIt() {
        val on = Prefs.isHotkeyServiceOn(this)
        title("Try it now")
        para(if (on) "Press the hotkey on your keyboard. This step ticks itself off." else
            "Turn on the hotkey in the previous step first, or skip: you can always open OrbitSearch from its icon.")
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(if (pal.dark) 0x0AFFFFFF else 0x080B1220) }
            setPadding(0, dpi(40f), 0, dpi(34f))
        }
        stage.addView(keyRow(listOf("Alt", "+", "Space"), big = true))
        val status = text(14f, pal.sub).apply {
            text = if (on) "Waiting for ${Prefs.hotkey(this@GuideActivity).label}…" else "Hotkey is off"
            gravity = Gravity.CENTER
            setPadding(0, dpi(20f), 0, 0)
        }
        stage.addView(status)
        body.addView(stage, spaced(22f))
        if (on) onHotkey = {
            status.text = "✓  It works. The OrbitSearch panel opened. Press Esc to close it."
            status.setTextColor(pal.accent)
            status.scaleX = 0.9f; status.scaleY = 0.9f
            status.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(Motion.springOut).start()
            onHotkey = null
            body.postDelayed({ if (step == 2) go(1) }, 1600)
        }
    }

    private fun look() {
        title("Pick a look")
        para("Frosted glass in light or charcoal dark, with a red accent. You can change this any time in OrbitSearch settings.")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val current = Prefs.theme(this)
        for ((id, name) in listOf(Prefs.THEME_SYSTEM to "Match system", Prefs.THEME_LIGHT to "Light", Prefs.THEME_DARK to "Dark")) {
            val chosen = id == current
            val swatchDark = id == Prefs.THEME_DARK || (id == Prefs.THEME_SYSTEM && pal.dark)
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpi(14f), dpi(14f), dpi(14f), dpi(14f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18f)
                    setColor(if (pal.dark) 0x0AFFFFFF else 0x080B1220)
                    if (chosen) setStroke(dpi(2f), pal.accent)
                }
                addView(View(this@GuideActivity).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12f)
                        setColor(if (swatchDark) 0xFF1C1C1E.toInt() else 0xFFF4F5F8.toInt())
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(90f)))
                addView(text(15f, pal.text, bold = true).apply { text = (if (chosen) "✓  " else "") + name; setPadding(0, dpi(10f), 0, 0) })
                isClickable = true
                setOnClickListener {
                    Prefs.setTheme(this@GuideActivity, id)
                    resumeAt = step
                    recreate()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(10f) })
        }
        body.addView(row, spaced(22f))
    }

    private fun extras() {
        title("Extras")
        para("Optional permissions for the tools. Each one is only used for that feature.")
        val nm = getSystemService(NotificationManager::class.java)
        val files = Environment.isExternalStorageManager()
        val write = Settings.System.canWrite(this)
        val dnd = nm.isNotificationPolicyAccessGranted
        body.addView(statusRow(files, "File search", "Find documents, PDFs and downloads by name, Space to preview",
            if (files) null else "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }, spaced(18f))
        body.addView(statusRow(dnd, "Do Not Disturb", "Lets \"dnd\" switch Do Not Disturb on and off",
            if (dnd) null else "Allow") { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }, spaced(10f))
        body.addView(statusRow(write, "Brightness & rotation", "\"brightness 70\" and auto-rotate run straight away",
            if (write) null else "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }, spaced(10f))
        body.addView(text(13f, pal.sub, bold = true).apply { text = "HANDY SHORTCUTS"; letterSpacing = 0.1f; setPadding(0, dpi(22f), 0, dpi(8f)) })
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (k in listOf("Ctrl /  Shortcuts", "Alt ⇧ S  Screenshot", "↓ Space  Preview")) keys.addView(
            text(13f, pal.text, bold = true).apply {
                text = k
                setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f))
                background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(pal.chip) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(8f) })
        body.addView(keys)
    }

    // ---------- pieces ----------

    private fun spaced(top: Float) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dpi(top) }

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, 650, false)
    }

    private fun keyRow(parts: List<String>, big: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or (if (big) Gravity.CENTER_HORIZONTAL else Gravity.START)
        for (p in parts) {
            val isKey = p !in listOf("+", "→", "type")
            addView(text(if (big) 26f else 20f, if (isKey) pal.text else pal.sub, bold = isKey).apply {
                text = p
                gravity = Gravity.CENTER
                if (isKey) {
                    setPadding(dpi(if (big) 30f else 20f), 0, dpi(if (big) 30f else 20f), 0)
                    background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(pal.chip) }
                } else setPadding(dpi(10f), 0, dpi(10f), 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(if (big) 80f else 60f)))
        }
    }

    private fun infoCard(t: String, d: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
        background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(if (pal.dark) 0x0AFFFFFF else 0x080B1220) }
        addView(text(14f, pal.text, bold = true).apply { text = t })
        addView(text(13f, pal.sub).apply { text = d; setLineSpacing(0f, 1.25f); setPadding(0, dpi(4f), 0, 0) })
    }

    private fun smallButton(label: String, onClick: () -> Unit) = text(13f, pal.text, bold = true).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
        background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(pal.chip) }
        setOnClickListener { onClick() }
    }

    private fun statusRow(on: Boolean, titleText: String, sub: String, action: String?, onAction: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(18f), dpi(14f), dpi(14f), dpi(14f))
        background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(if (pal.dark) 0x0DFFFFFF else 0x0A0B1220) }
        addView(View(this@GuideActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) 0xFF3FB97A.toInt() else 0xFFE0A34A.toInt()) }
        }, LinearLayout.LayoutParams(dpi(10f), dpi(10f)).apply { marginEnd = dpi(14f) })
        addView(LinearLayout(this@GuideActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(16f, pal.text, bold = true).apply { text = titleText })
            addView(text(13f, pal.sub).apply { text = sub })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) addView(text(14f, pal.onAccent, bold = true).apply {
            text = action
            gravity = Gravity.CENTER
            setPadding(dpi(16f), 0, dpi(16f), 0)
            background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(pal.accent) }
            setOnClickListener { onAction() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(44f)))
        else addView(text(14f, 0xFF3FB97A.toInt(), bold = true).apply { text = "✓ On" })
    }
}
