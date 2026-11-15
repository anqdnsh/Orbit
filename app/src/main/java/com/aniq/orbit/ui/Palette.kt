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
    val accent: Int,        // buttons and "on" status
    val onAccent: Int,
    val selection: Int,     // highlighted result row / active filter chip
    val onSelection: Int,
    val onSelectionSub: Int,
    val pageBg: Int,        // settings screen
    val card: Int,
) {
    companion object {
        val LIGHT = Palette(
            dark = false,
            fill = 0x99FFFFFF.toInt(),
            fillOpaque = 0xF5F7F9FD.toInt(),
            strokeTop = 0xF2FFFFFF.toInt(),
            strokeBottom = 0x40FFFFFF,
            text = 0xFF0B1220.toInt(),
            sub = 0xFF3D4A63.toInt(),
            divider = 0x1A0B1220,
            chip = 0x99FFFFFF.toInt(),
            tile = 0x99FFFFFF.toInt(),
            accent = 0xFFC42B33.toInt(),        // deep Raycast-style red
            onAccent = 0xFFFFFFFF.toInt(),
            selection = 0x140B1220,
            onSelection = 0xFF0B1220.toInt(),
            onSelectionSub = 0xFF3D4A63.toInt(),
            pageBg = 0xFFEEF1F7.toInt(),
            card = 0xFFFFFFFF.toInt(),
        )

        /** Neutral charcoal, Raycast-style: no blue cast, grey highlight, red accent. */
        val DARK = Palette(
            dark = true,
            fill = 0xC71C1C1E.toInt(),
            fillOpaque = 0xF71C1C1E.toInt(),
            strokeTop = 0x47FFFFFF,
            strokeBottom = 0x0DFFFFFF,
            text = 0xFFEDEDED.toInt(),
            sub = 0xFF9B9B9F.toInt(),
            divider = 0x14FFFFFF,
            chip = 0x12FFFFFF,
            tile = 0x14FFFFFF,
            accent = 0xFFD7373F.toInt(),        // red, white text passes contrast
            onAccent = 0xFFFFFFFF.toInt(),
            selection = 0x26FFFFFF,
            onSelection = 0xFFFFFFFF.toInt(),
            onSelectionSub = 0xFFB4B4B8.toInt(),
            pageBg = 0xFF161617.toInt(),
            card = 0xFF202022.toInt(),
        )

        fun isDark(ctx: Context): Boolean = when (Prefs.theme(ctx)) {
            Prefs.THEME_DARK -> true
            Prefs.THEME_LIGHT -> false
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }

        fun current(ctx: Context): Palette = if (isDark(ctx)) DARK else LIGHT

        /** "Reduce effects": no blur, solid panel. Saves GPU work and battery. */
        fun reduced(ctx: Context): Boolean = Prefs.of(ctx).getBoolean("reduce_effects", false)
    }
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
