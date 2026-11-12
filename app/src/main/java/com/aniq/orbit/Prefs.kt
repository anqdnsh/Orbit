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
