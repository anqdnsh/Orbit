package com.aniq.orbit

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.util.Locale

/** The health card: live numbers only, read straight from Android. No permission needed. */
object Health {
    class Snapshot(val title: String, val subtitle: String)

    fun read(ctx: Context): Snapshot {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = 1_000_000_000.0
        val used = (mi.totalMem - mi.availMem) / gb
        val total = mi.totalMem / gb
        val free = try { StatFs(Environment.getDataDirectory().path).availableBytes / gb } catch (_: Exception) { -1.0 }

        val b = ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
        val charging = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val days = SystemClock.elapsedRealtime() / 86_400_000L
        val hours = SystemClock.elapsedRealtime() / 3_600_000L

        val title = buildString {
            append(String.format(Locale.US, "RAM %.1f / %.0f GB used", used, total))
            if (free >= 0) append(String.format(Locale.US, "  ·  %.0f GB storage free", free))
        }
        val subtitle = buildList {
            if (level >= 0) add("Battery $level%")
            if (temp > 0) add(String.format(Locale.US, "%.0f°C", temp))
            if (charging) add("Charging")
            add(if (days >= 1) "On for $days day${if (days > 1) "s" else ""}" else "On for $hours h")
            if (mi.lowMemory) add("Low memory")
            if (days >= 3) add("Restart recommended")
        }.joinToString("  ·  ")
        return Snapshot(title, subtitle)
    }
}

/** Samsung Battery and device care screens. The exact screen names vary by One UI version, so try several. */
object DeviceCare {
    private const val PKG = "com.samsung.android.lool"

    private val dashboard = listOf(
        Intent().setComponent(ComponentName(PKG, "com.samsung.android.sm.ui.dashboard.SmartManagerDashBoardActivity")),
        Intent("com.samsung.android.sm.ACTION_DASHBOARD"),
        Intent("android.intent.action.POWER_USAGE_SUMMARY"),
    )

    private val memory = listOf(
        Intent().setComponent(ComponentName(PKG, "com.samsung.android.sm.ui.ram.RamActivity")),
        Intent("com.samsung.android.sm.ACTION_RAM"),
    ) + dashboard

    fun openDashboard(ctx: Context) = openFirst(ctx, dashboard)
    fun openMemory(ctx: Context) = openFirst(ctx, memory)

    private fun openFirst(ctx: Context, list: List<Intent>): Boolean {
        for (i in list + Intent(android.provider.Settings.ACTION_SETTINGS)) {
            try {
                ctx.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
                // Not on this One UI version (or not allowed): try the next one.
            }
        }
        return false
    }
}

/** Is Samsung DeX on? Uses the flag Samsung documents for apps; null if this One UI doesn't expose it. */
object Dex {
    fun isOn(ctx: Context): Boolean? = try {
        val cfg = ctx.resources.configuration
        val cls = cfg.javaClass
        val on = cls.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null)
        val cur = cls.getField("semDesktopModeEnabled").getInt(cfg)
        cur == on
    } catch (_: Throwable) {
        null
    }
}
