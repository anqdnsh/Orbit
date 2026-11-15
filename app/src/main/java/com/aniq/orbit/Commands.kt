package com.aniq.orbit

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import com.aniq.orbit.search.Glyph
import com.aniq.orbit.tools.FocusTimer
import com.aniq.orbit.tools.KeepAwake
import com.aniq.orbit.search.Text
import kotlin.math.roundToInt

/**
 * System controls. Each one does one of three things:
 *  - runs straight away (via the hotkey service or normal Android APIs),
 *  - runs straight away once you have granted a one-time permission,
 *  - opens the right Settings page or pop-up panel when Android doesn't let apps do it.
 */
enum class How { GLOBAL, MEDIA, VOLUME, VOLUME_SET, TORCH, BRIGHTNESS, ROTATION, DND, SETTINGS, HEALTH, DEVICE_CARE, DEX,
    AREA, FOCUS, TIMER, TIMER_SHOW, TIMER_END, AWAKE, AWAKE_OFF }

class CommandEntry(
    val id: String,
    val title: String,
    keywords: List<String>,
    val how: How,
    val arg: Int = 0,
    val glyph: Glyph = Glyph.BOLT,
    val settings: List<String> = emptyList(),
    /** Replaces the status tag, e.g. to explain the power menu. */
    val note: String? = null,
) {
    val norm = Text.normalize(title)
    val initials = Text.initials(title)
    val keywordNorms = keywords.map { Text.normalize(it) }
}

object Commands {
    private val main = Handler(Looper.getMainLooper())

    private fun g(id: String, title: String, kw: List<String>, action: Int, glyph: Glyph = Glyph.BOLT, note: String? = null) =
        CommandEntry(id, title, kw, How.GLOBAL, action, glyph, note = note)

    private fun page(id: String, title: String, kw: List<String>, vararg actions: String) =
        CommandEntry(id, title, kw, How.SETTINGS, glyph = Glyph.GEAR, settings = actions.toList() + "android.settings.SETTINGS")

    val VOLUME_SET = CommandEntry("volume_set", "Set volume", listOf(), How.VOLUME_SET)
    val BRIGHTNESS_SET = CommandEntry("brightness_set", "Set brightness", listOf(), How.BRIGHTNESS, arg = 1)
    val FOCUS_SET = CommandEntry("focus_set", "Focus", listOf(), How.FOCUS, glyph = Glyph.TIMER)
    val TIMER_SET = CommandEntry("timer_set", "Timer", listOf(), How.TIMER, glyph = Glyph.TIMER)
    val AWAKE_SET = CommandEntry("awake_set", "Keep awake", listOf(), How.AWAKE, glyph = Glyph.SUN)

    val entries: List<CommandEntry> = listOf(
        // Power: Android lets apps open the power menu; you tap Restart / Power off there.
        g("restart", "Restart", listOf("reboot", "restart tablet"), AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
            Glyph.POWER, "Opens the power menu, then tap Restart"),
        g("shutdown", "Shut down", listOf("power off", "shutdown", "turn off"), AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
            Glyph.POWER, "Opens the power menu, then tap Power off"),
        g("power_menu", "Power menu", listOf("power"), AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, Glyph.POWER),
        g("lock", "Lock screen", listOf("lock", "sleep", "screen off"), AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, Glyph.POWER),
        g("screenshot", "Take screenshot", listOf("screenshot", "screen shot", "capture"), AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT),
        g("notifications", "Notifications panel", listOf("notifications", "shade"), AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
        g("quick_settings", "Quick Settings panel", listOf("quick settings", "toggles"), AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
        g("recents", "Recent apps", listOf("recents", "overview", "multitask"), AccessibilityService.GLOBAL_ACTION_RECENTS),
        g("quit_all", "Quit all apps", listOf("quit all", "close all", "clear recents", "close apps", "kill apps", "clear all"),
            AccessibilityService.GLOBAL_ACTION_RECENTS, Glyph.POWER, "Opens Recent apps, then tap Close all"),

        // Device optimise: ORBIT reads the numbers, Samsung's Device care does the cleaning.
        CommandEntry("health", "Device health", listOf("health", "optimise", "optimize", "ram", "memory", "storage", "battery", "temperature", "status"),
            How.HEALTH, glyph = Glyph.PULSE),
        CommandEntry("clean_ram", "Clean RAM", listOf("clean ram", "free memory", "memory", "boost", "ram", "close background apps"),
            How.DEVICE_CARE, arg = 1, glyph = Glyph.BOLT),
        CommandEntry("optimise", "Optimise now", listOf("optimise", "optimize", "device care", "speed up", "clean"),
            How.DEVICE_CARE, arg = 0, glyph = Glyph.BOLT),
        page("free_storage", "Free up storage", listOf("storage", "free space", "clean storage", "cleanup", "delete files"),
            "android.os.storage.action.MANAGE_STORAGE", "android.settings.INTERNAL_STORAGE_SETTINGS"),
        page("battery_usage", "Battery usage", listOf("battery", "battery usage", "drain"),
            "android.intent.action.POWER_USAGE_SUMMARY"),

        // Laptop-style tools.
        CommandEntry("area", "Screenshot area", listOf("area screenshot", "snip", "crop screenshot", "capture area", "screenshot", "cmd shift 4"),
            How.AREA, glyph = Glyph.CROP),
        CommandEntry("focus25", "Focus for 25 min", listOf("focus", "pomodoro", "deep work", "study"), How.FOCUS, arg = 25, glyph = Glyph.TIMER),
        CommandEntry("focus50", "Focus for 50 min", listOf("focus", "deep work", "study"), How.FOCUS, arg = 50, glyph = Glyph.TIMER),
        CommandEntry("timer5", "Timer for 5 min", listOf("timer", "countdown"), How.TIMER, arg = 5, glyph = Glyph.TIMER),
        CommandEntry("timer10", "Timer for 10 min", listOf("timer", "countdown"), How.TIMER, arg = 10, glyph = Glyph.TIMER),
        CommandEntry("timer_show", "Show timer", listOf("timer", "focus", "show timer"), How.TIMER_SHOW, glyph = Glyph.TIMER),
        CommandEntry("timer_end", "End timer", listOf("stop timer", "end timer", "cancel timer", "end focus"), How.TIMER_END, glyph = Glyph.TIMER),
        CommandEntry("awake30", "Keep awake for 30 min", listOf("keep awake", "caffeinate", "awake", "stay on", "no sleep"), How.AWAKE, arg = 30, glyph = Glyph.SUN),
        CommandEntry("awake60", "Keep awake for 1 hour", listOf("keep awake", "caffeinate", "awake"), How.AWAKE, arg = 60, glyph = Glyph.SUN),
        CommandEntry("awake0", "Keep awake until I turn it off", listOf("keep awake", "caffeinate", "awake", "always on"), How.AWAKE, arg = 0, glyph = Glyph.SUN),
        CommandEntry("awake_off", "Keep awake off", listOf("keep awake off", "caffeinate off", "let sleep", "awake off"), How.AWAKE_OFF, glyph = Glyph.SUN),

        // DeX: no app can switch it directly, so open Quick Settings with the DeX tile ready.
        CommandEntry("dex", "DeX", listOf("dex", "desktop mode", "samsung dex", "enter dex", "exit dex"), How.DEX, glyph = Glyph.ORBIT),
        g("split", "Split screen", listOf("split", "side by side", "multi window"), AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN),
        g("home", "Go home", listOf("home", "desktop"), AccessibilityService.GLOBAL_ACTION_HOME),

        // Media and sound: normal Android APIs, no permission.
        CommandEntry("play_pause", "Play / pause", listOf("play", "pause", "music"), How.MEDIA, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        CommandEntry("next", "Next track", listOf("next", "skip"), How.MEDIA, KeyEvent.KEYCODE_MEDIA_NEXT),
        CommandEntry("previous", "Previous track", listOf("previous", "prev"), How.MEDIA, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        CommandEntry("vol_up", "Volume up", listOf("louder", "volume"), How.VOLUME, AudioManager.ADJUST_RAISE),
        CommandEntry("vol_down", "Volume down", listOf("quieter", "volume"), How.VOLUME, AudioManager.ADJUST_LOWER),
        CommandEntry("mute", "Mute / unmute", listOf("mute", "unmute", "silent"), How.VOLUME, AudioManager.ADJUST_TOGGLE_MUTE),
        CommandEntry("torch", "Torch on / off", listOf("torch", "flashlight", "flash"), How.TORCH),

        // One-time permission, then direct.
        CommandEntry("auto_brightness", "Auto brightness on / off", listOf("brightness", "adaptive brightness"), How.BRIGHTNESS, arg = 0),
        CommandEntry("rotation", "Auto-rotate on / off", listOf("rotate", "rotation", "rotation lock"), How.ROTATION),
        CommandEntry("dnd", "Do Not Disturb on / off", listOf("dnd", "focus", "silence", "do not disturb"), How.DND),

        // Android doesn't let apps flip these: open the right panel or page.
        page("wifi_toggle", "Wi-Fi on / off", listOf("wifi", "wifi on", "wifi off", "wireless"),
            "android.settings.panel.action.WIFI", "android.settings.WIFI_SETTINGS"),
        page("bt_toggle", "Bluetooth on / off", listOf("bluetooth", "bt"), "android.settings.BLUETOOTH_SETTINGS"),
        page("dark", "Dark mode on / off", listOf("dark", "dark mode", "light mode", "night mode"),
            "android.settings.DARK_THEME_SETTINGS", "android.settings.DISPLAY_SETTINGS"),
        page("battery_saver", "Battery saver", listOf("power saving", "battery saver"),
            "android.settings.BATTERY_SAVER_SETTINGS", "android.intent.action.POWER_USAGE_SUMMARY"),
        page("hotspot", "Hotspot", listOf("tethering", "mobile hotspot"),
            "android.settings.TETHER_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
        page("location_toggle", "Location on / off", listOf("gps", "location"), "android.settings.LOCATION_SOURCE_SETTINGS"),
        page("airplane_toggle", "Airplane mode on / off", listOf("flight mode", "airplane", "aeroplane"),
            "android.settings.AIRPLANE_MODE_SETTINGS"),
        page("nfc_toggle", "NFC on / off", listOf("nfc"), "android.settings.panel.action.NFC", "android.settings.NFC_SETTINGS"),
    )

    private val volRegex = Regex("^(?:vol|volume)\\s*(\\d{1,3})\\s*%?$", RegexOption.IGNORE_CASE)
    private val brightRegex = Regex("^(?:bright|brightness)\\s*(\\d{1,3})\\s*%?$", RegexOption.IGNORE_CASE)

    /** "volume 40" or "brightness 70%" → a command with a value. */
    fun parametric(raw: String): Pair<CommandEntry, Int>? {
        val s = raw.trim()
        volRegex.find(s)?.let { return VOLUME_SET to it.groupValues[1].toInt().coerceIn(0, 100) }
        brightRegex.find(s)?.let { return BRIGHTNESS_SET to it.groupValues[1].toInt().coerceIn(0, 100) }
        focusRegex.find(s)?.let { return FOCUS_SET to minutes(it.groupValues[1], it.groupValues[2], 25) }
        timerRegex.find(s)?.let { return TIMER_SET to minutes(it.groupValues[1], it.groupValues[2], 5) }
        awakeRegex.find(s)?.let { return AWAKE_SET to minutes(it.groupValues[1], it.groupValues[2], 30) }
        return null
    }

    private val unitPart = "\\s*(m|min|mins|minutes?|h|hr|hrs|hours?)?"
    private val focusRegex = Regex("^(?:focus|pomodoro)\\s+(\\d{1,3})$unitPart$", RegexOption.IGNORE_CASE)
    private val timerRegex = Regex("^timer\\s+(\\d{1,3})$unitPart$", RegexOption.IGNORE_CASE)
    private val awakeRegex = Regex("^(?:awake|caffeinate|keep awake)\\s+(\\d{1,3})$unitPart$", RegexOption.IGNORE_CASE)

    private fun minutes(num: String, unit: String, def: Int): Int {
        val n = num.toIntOrNull() ?: return def
        return (if (unit.startsWith("h", ignoreCase = true)) n * 60 else n).coerceIn(1, 24 * 60)
    }

    private fun span(m: Int) = if (m % 60 == 0 && m >= 60) "${m / 60} hour${if (m >= 120) "s" else ""}" else "$m min"

    fun title(ctx: Context, e: CommandEntry, param: Int?): String = when {
        e.how == How.HEALTH -> Health.read(ctx).title
        e.how == How.DEX -> when (Dex.isOn(ctx)) { true -> "Exit DeX"; false -> "Enter DeX"; null -> "Enter / exit DeX" }
        e.how == How.TIMER_SHOW && FocusTimer.state != FocusTimer.State.IDLE ->
            "Show timer (${FocusTimer.label}, ${(FocusTimer.remaining() + 59_999) / 60_000} min left)"
        e.how == How.FOCUS && param != null -> "Focus for ${span(param)}"
        e.how == How.TIMER && param != null -> "Timer for ${span(param)}"
        e.how == How.AWAKE && param != null -> "Keep awake for ${span(param)}"
        param == null -> e.title
        e.how == How.VOLUME_SET -> "Set volume to $param%"
        else -> "Set brightness to $param%"
    }

    /** The tag shown under each command, so you know what Enter will do. */
    fun status(ctx: Context, e: CommandEntry): String {
        e.note?.let { return if (OrbitAccessibilityService.instance != null) it else "Needs the OrbitSearch hotkey switch (opens setup)" }
        return when (e.how) {
            How.GLOBAL -> if (OrbitAccessibilityService.instance != null) "System · Runs now"
            else "Needs the OrbitSearch hotkey switch (opens setup)"
            How.MEDIA, How.VOLUME, How.VOLUME_SET -> "System · Runs now"
            How.TORCH -> if (torchId != null || !torchChecked) "System · Runs now" else "No torch on this device"
            How.BRIGHTNESS, How.ROTATION ->
                if (Settings.System.canWrite(ctx)) "System · Runs now" else "Asks permission once, then runs"
            How.DND ->
                if (ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) "System · Runs now"
                else "Asks permission once, then runs"
            How.SETTINGS -> if (e.settings.firstOrNull()?.contains(".panel.") == true) "Opens Settings panel" else "Opens Settings"
            How.HEALTH -> Health.read(ctx).subtitle + "  ·  ↵ Device care"
            How.DEVICE_CARE -> if (e.arg == 1) "Opens Device care › Memory, then tap Clean now" else "Opens Device care"
            How.DEX -> if (OrbitAccessibilityService.instance != null) "System · Switches straight away"
            else "Needs the OrbitSearch hotkey switch (opens setup)"
            How.AREA, How.FOCUS, How.TIMER, How.TIMER_SHOW, How.AWAKE ->
                if (OrbitAccessibilityService.instance == null) "Needs the OrbitSearch hotkey switch (opens setup)"
                else when (e.how) {
                    How.AREA -> "Drag a box  ·  saved and copied  ·  Alt + Shift + S anywhere"
                    How.FOCUS -> "Ring timer  ·  chimes when done"
                    How.TIMER -> "Ring timer  ·  chimes when done"
                    How.TIMER_SHOW -> if (FocusTimer.state == FocusTimer.State.IDLE) "No timer running" else "Opens the timer"
                    else -> if (KeepAwake.isOn) "Now: ${KeepAwake.label()}" else "Screen stays on, then sleeps normally"
                }
            How.TIMER_END -> if (FocusTimer.state == FocusTimer.State.IDLE) "No timer running" else "Stops the timer"
            How.AWAKE_OFF -> "Now: ${KeepAwake.label()}"
        }
    }

    /** Returns true when the ORBIT panel should close. */
    fun run(ctx: Context, e: CommandEntry, param: Int?): Boolean = when (e.how) {
        How.HEALTH -> DeviceCare.openDashboard(ctx)
        How.AREA, How.FOCUS, How.TIMER, How.TIMER_SHOW, How.AWAKE -> {
            val svc = OrbitAccessibilityService.instance
            if (svc == null) {
                toast(ctx, "Turn on the OrbitSearch hotkey switch to use this")
                ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else when (e.how) {
                How.AREA -> svc.area.start()
                How.FOCUS, How.TIMER -> {
                    FocusTimer.start(ctx, param ?: e.arg, isFocus = e.how == How.FOCUS)
                    main.postDelayed({ svc.timer.showCard() }, 180)
                }
                How.TIMER_SHOW -> if (FocusTimer.state != FocusTimer.State.IDLE) main.postDelayed({ svc.timer.showCard() }, 180)
                else -> {
                    val m = param ?: e.arg
                    KeepAwake.start(svc, if (m == 0) null else m)
                    toast(ctx, "Keep awake: " + KeepAwake.label())
                }
            }
            true
        }
        How.TIMER_END -> { FocusTimer.end(ctx); true }
        How.AWAKE_OFF -> { KeepAwake.stop(); toast(ctx, "Keep awake off"); true }
        How.DEVICE_CARE -> if (e.arg == 1) DeviceCare.openMemory(ctx) else DeviceCare.openDashboard(ctx)
        How.DEX -> {
            val svc = OrbitAccessibilityService.instance
            if (svc == null) {
                toast(ctx, "Turn on the OrbitSearch hotkey switch to use this")
                ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                // Let the panel close first so Quick Settings opens over a clean screen.
                main.postDelayed({ com.aniq.orbit.tools.DexSwitch.toggle(svc) }, 120)
            }
            true
        }
        How.GLOBAL -> {
            val svc = OrbitAccessibilityService.instance
            if (svc == null) {
                toast(ctx, "Turn on the OrbitSearch hotkey switch to use this")
                ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                // Let the panel close first so it isn't in the screenshot or behind the power menu.
                main.postDelayed({ svc.performGlobalAction(e.arg) }, 220)
            }
            true
        }
        How.MEDIA -> {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, e.arg))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, e.arg))
            true
        }
        How.VOLUME -> {
            ctx.getSystemService(AudioManager::class.java)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, e.arg, AudioManager.FLAG_SHOW_UI)
            true
        }
        How.VOLUME_SET -> {
            val am = ctx.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * (param ?: 50) / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
            true
        }
        How.TORCH -> toggleTorch(ctx)
        How.BRIGHTNESS -> withWriteSettings(ctx) {
            val r = ctx.contentResolver
            if (e.arg == 0 && param == null) {
                val auto = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                Settings.System.putInt(
                    r, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                )
                toast(ctx, if (auto) "Auto brightness off" else "Auto brightness on")
            } else {
                val pct = param ?: 50
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100f).roundToInt().coerceIn(1, 255))
                toast(ctx, "Brightness $pct%")
            }
        }
        How.ROTATION -> withWriteSettings(ctx) {
            val r = ctx.contentResolver
            val on = Settings.System.getInt(r, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
            Settings.System.putInt(r, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
            toast(ctx, if (on) "Auto-rotate off" else "Auto-rotate on")
        }
        How.DND -> {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!nm.isNotificationPolicyAccessGranted) {
                toast(ctx, "Allow OrbitSearch, then run the command again")
                openIntent(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } else {
                val on = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(
                    if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
                )
                toast(ctx, if (on) "Do Not Disturb off" else "Do Not Disturb on")
            }
            true
        }
        How.SETTINGS -> {
            val pm = ctx.packageManager
            val action = e.settings.firstOrNull { pm.resolveActivity(Intent(it), 0) != null }
            if (action != null) openIntent(ctx, Intent(action))
            action != null
        }
    }

    private inline fun withWriteSettings(ctx: Context, block: () -> Unit): Boolean {
        if (Settings.System.canWrite(ctx)) {
            block()
        } else {
            toast(ctx, "Allow OrbitSearch to modify system settings, then run the command again")
            openIntent(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }
        return true
    }

    // ---------- torch ----------

    @Volatile private var torchId: String? = null
    @Volatile private var torchChecked = false
    @Volatile private var torchOn = false

    /** Finds the flash and tracks whether it is on. Called once in the background at app start. */
    fun init(ctx: Context) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        torchId = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { null }
        torchChecked = true
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchId) torchOn = enabled
            }
        }, main)
    }

    private fun toggleTorch(ctx: Context): Boolean {
        val id = torchId
        if (id == null) { toast(ctx, "No torch found on this device"); return true }
        return try {
            ctx.getSystemService(CameraManager::class.java).setTorchMode(id, !torchOn)
            true
        } catch (_: Exception) {
            toast(ctx, "Torch is busy (the camera may be in use)")
            true
        }
    }

    // ---------- helpers ----------

    private fun openIntent(ctx: Context, i: Intent) {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }
}
