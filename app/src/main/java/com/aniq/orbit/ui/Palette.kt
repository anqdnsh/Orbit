package com.aniq.orbit.ui

import android.content.Context
import android.content.res.Configuration
import com.aniq.orbit.Prefs

/** Light and dark liquid-glass colours. Alpha in `fill` is what lets the blur show through. */
class Palette(
    val dark: Boolean,
    val fill: Int,          // panel tint when blur is on
    val fillOpaque: Int,    // panel tint when the system turns blur off (battery saver etc.)
    val strokeTop: Int,     // bright specular edge at the top of the glass
    val strokeBottom: Int,
    val text: Int,
    val sub: Int,
    val divider: Int,
    val chip: Int,
    val tile: Int,
    val accent: Int,
    val onAccent: Int,
    val pageBg: Int,        // settings screen
    val card: Int,
) {
    companion object {
        val LIGHT = Palette(
            dark = false,
            fill = 0xA6FFFFFF.toInt(),
            fillOpaque = 0xF5F7F9FD.toInt(),
            strokeTop = 0xF2FFFFFF.toInt(),
            strokeBottom = 0x40FFFFFF,
            text = 0xFF0B1220.toInt(),
            sub = 0xFF3D4A63.toInt(),
            divider = 0x1A0B1220,
            chip = 0x99FFFFFF.toInt(),
            tile = 0x99FFFFFF.toInt(),
            accent = 0xFF1F5FD6.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            pageBg = 0xFFEEF1F7.toInt(),
            card = 0xFFFFFFFF.toInt(),
        )

        val DARK = Palette(
            dark = true,
            fill = 0x9E141925.toInt(),
            fillOpaque = 0xF5141925.toInt(),
            strokeTop = 0x66FFFFFF,
            strokeBottom = 0x14FFFFFF,
            text = 0xFFF2F5FA.toInt(),
            sub = 0xFFA9B4C8.toInt(),
            divider = 0x1FFFFFFF,
            chip = 0x1FFFFFFF,
            tile = 0x1FFFFFFF,
            accent = 0xFF2F6BE0.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            pageBg = 0xFF0E1320.toInt(),
            card = 0xFF1A2130.toInt(),
        )

        fun isDark(ctx: Context): Boolean = when (Prefs.theme(ctx)) {
            Prefs.THEME_DARK -> true
            Prefs.THEME_LIGHT -> false
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }

        fun current(ctx: Context): Palette = if (isDark(ctx)) DARK else LIGHT
    }
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
