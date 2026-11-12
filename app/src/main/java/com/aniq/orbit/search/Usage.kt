package com.aniq.orbit.search

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.ln

/** Remembers what you open so frequent and recent picks rise to the top. Stays on the device. */
object Usage {
    private lateinit var prefs: SharedPreferences
    private val counts = HashMap<String, Int>()
    private val last = HashMap<String, Long>()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("usage", Context.MODE_PRIVATE)
        for ((k, v) in prefs.all) {
            val parts = (v as? String)?.split('|') ?: continue
            counts[k] = parts[0].toIntOrNull() ?: 0
            last[k] = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        }
    }

    fun count(key: String): Int = counts[key] ?: 0

    fun boost(key: String): Int {
        val c = counts[key] ?: return 0
        var b = (60 * ln(1.0 + c)).toInt()
        val age = System.currentTimeMillis() - (last[key] ?: 0L)
        b += when {
            age < 6 * HOUR -> 50
            age < 72 * HOUR -> 20
            else -> 0
        }
        return b.coerceAtMost(220)
    }

    fun record(key: String) {
        val c = (counts[key] ?: 0) + 1
        val now = System.currentTimeMillis()
        counts[key] = c
        last[key] = now
        prefs.edit().putString(key, "$c|$now").apply()
    }

    private const val HOUR = 3_600_000L
}
