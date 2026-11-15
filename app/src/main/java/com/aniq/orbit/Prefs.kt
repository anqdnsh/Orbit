package com.aniq.orbit

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.view.KeyEvent

object Prefs {
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    fun theme(ctx: Context): String = of(ctx).getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
    fun setTheme(ctx: Context, v: String) = of(ctx).edit().putString("theme", v).apply()

    fun hotkey(ctx: Context): Hotkey = Hotkey.fromId(of(ctx).getString("hotkey", null))
    fun setHotkey(ctx: Context, h: Hotkey) = of(ctx).edit().putString("hotkey", h.id).apply()

    // ---------- focus timer ----------

    fun timerFace(ctx: Context): TimerFaceKind = TimerFaceKind.fromId(of(ctx).getString("timer_face", null))
    fun setTimerFace(ctx: Context, f: TimerFaceKind) = of(ctx).edit().putString("timer_face", f.id).apply()
    fun timerChimeOnSilent(ctx: Context) = of(ctx).getBoolean("timer_chime_silent", true)
    fun setTimerChimeOnSilent(ctx: Context, v: Boolean) = of(ctx).edit().putBoolean("timer_chime_silent", v).apply()
    fun timerTicks(ctx: Context) = of(ctx).getBoolean("timer_ticks", true)
    fun setTimerTicks(ctx: Context, v: Boolean) = of(ctx).edit().putBoolean("timer_ticks", v).apply()
    fun timerMilestones(ctx: Context) = of(ctx).getBoolean("timer_milestones", true)
    fun setTimerMilestones(ctx: Context, v: Boolean) = of(ctx).edit().putBoolean("timer_milestones", v).apply()

    /**
     * One-time clean-up for anyone who used Quick AI before it was removed: deletes its saved
     * settings and encrypted API keys, and the Keystore key that locked them.
     */
    fun purgeOldAi(ctx: Context) {
        val p = of(ctx)
        if (p.getBoolean("ai_purged", false)) return
        val e = p.edit()
        p.all.keys.filter { it.startsWith("ai_") }.forEach { e.remove(it) }
        e.putBoolean("ai_purged", true).apply()
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias("orbit_ai_keys")) ks.deleteEntry("orbit_ai_keys")
        }
    }

    /** True when the user has switched on ORBIT's accessibility service in Settings. */
    fun isHotkeyServiceOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(ctx, OrbitAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

enum class Hotkey(val id: String, val label: String) {
    ALT_SPACE("alt_space", "Alt + Space"),
    CTRL_ALT_SPACE("ctrl_alt_space", "Ctrl + Alt + Space"),
    META_SPACE("meta_space", "Meta + Space");

    fun matches(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_SPACE) return false
        return when (this) {
            ALT_SPACE -> e.isAltPressed && !e.isCtrlPressed && !e.isMetaPressed && !e.isShiftPressed
            CTRL_ALT_SPACE -> e.isAltPressed && e.isCtrlPressed && !e.isMetaPressed
            META_SPACE -> e.isMetaPressed && !e.isCtrlPressed && !e.isAltPressed
        }
    }

    companion object {
        fun fromId(id: String?): Hotkey = entries.firstOrNull { it.id == id } ?: ALT_SPACE
    }
}

/**
 * Features you can switch off in OrbitSearch settings. All start on. Off means the results,
 * commands and shortcuts for that feature disappear, and it does no work at all.
 */
enum class Feature(val id: String, val label: String, val detail: String, val group: Group) {
    FILES("files", "File search", "Find documents, PDFs and downloads by name. Space previews them", Group.SEARCH),
    SETTINGS_PAGES("settings_pages", "Settings pages", "Jump straight to Wi-Fi, Bluetooth, Display and other Settings pages", Group.SEARCH),
    COMMANDS("commands", "System commands", "Restart, lock, volume, brightness, torch, Do Not Disturb and more", Group.SEARCH),
    CALC("calc", "Calculator", "Type sums like 128*1.06 for an instant answer", Group.SEARCH),
    CURRENCY("currency", "Currency conversion", "Type 100 usd to myr. Fetches rates once a day", Group.SEARCH),
    WEB("web", "Web search", "A “Search the web” row at the end of results", Group.SEARCH),
    AREA("area", "Area screenshot", "Alt + Shift + S anywhere, or type “snip”", Group.TOOLS),
    TIMERS("timers", "Focus timer & timers", "“focus 25”, “timer 10”, with an on-screen countdown", Group.TOOLS),
    AWAKE("awake", "Keep awake", "“awake 1h” keeps the screen on for a while", Group.TOOLS),
    DEVICE("device", "Device tools", "Device health, Clean RAM and Optimise now", Group.TOOLS);

    enum class Group(val title: String) { SEARCH("In search results"), TOOLS("Tools") }

    fun on(ctx: Context): Boolean = Prefs.of(ctx).getBoolean("feat_$id", true)
    fun set(ctx: Context, v: Boolean) = Prefs.of(ctx).edit().putBoolean("feat_$id", v).apply()

    companion object {
        /** Which feature a command belongs to; null = always part of System commands. */
        fun forCommand(id: String): Feature? = when {
            id == "area" -> AREA
            id.startsWith("focus") || id.startsWith("timer") -> TIMERS
            id.startsWith("awake") -> AWAKE
            id == "health" || id == "clean_ram" || id == "optimise" -> DEVICE
            else -> null
        }

        /** A command shows only if System commands is on and its own tool (if any) is on. */
        fun commandOn(ctx: Context, id: String): Boolean {
            val tool = forCommand(id)
            return if (tool != null) tool.on(ctx) else COMMANDS.on(ctx)
        }
    }
}

/** The focus timer's faces. Picked on the timer itself (swipe, the dots, or ← →). */
enum class TimerFaceKind(val id: String, val label: String) {
    CLASSIC("classic", "Classic"),
    HOLE("hole", "Black hole"),
    ORBIT("orbit", "Orbit");

    fun next(step: Int): TimerFaceKind = entries[(ordinal + step).mod(entries.size)]

    companion object {
        fun fromId(id: String?): TimerFaceKind = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}
