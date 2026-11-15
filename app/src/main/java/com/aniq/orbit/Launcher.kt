package com.aniq.orbit

import android.app.ActivityOptions
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Process
import com.aniq.orbit.search.CalcHistory
import com.aniq.orbit.search.Currency
import com.aniq.orbit.search.Item
import android.provider.Settings
import com.aniq.orbit.search.Usage

/** Opens a result. Pop-up and side-by-side are requests: One UI decides if the current mode allows them. */
object Launcher {
    enum class Mode { NORMAL, POPUP, SIDE_BY_SIDE }

    fun open(ctx: Context, item: Item, mode: Mode): Boolean {
        val ok = try {
            when (item) {
                is Item.App -> openApp(ctx, item, mode)
                is Item.Setting -> openSetting(ctx, item, mode)
                is Item.Command -> Commands.run(ctx, item.entry, item.param)
                is Item.Calc -> { CalcHistory.add(ctx, item.expr, item.value); copy(ctx, item.value) }
                is Item.Money -> item.value?.let { v ->
                    val plain = Currency.format(v, item.q.to)
                    CalcHistory.add(ctx, item.q.amount.toString().removeSuffix(".0") + " " + item.q.from + " → " + item.q.to, plain)
                    copy(ctx, "%.2f".format(java.util.Locale.US, v))
                } ?: false
                is Item.File -> openFile(ctx, item, mode)
                is Item.Permission -> {
                    val i = Intent(item.action)
                    if (item.action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION ||
                        item.action == Settings.ACTION_MANAGE_WRITE_SETTINGS
                    ) i.data = Uri.parse("package:" + ctx.packageName)
                    start(ctx, i, Mode.NORMAL)
                }
                is Item.Ask -> false // handled inside the panel (Quick AI)
                is Item.Web -> openWeb(ctx, item.query, mode)
                Item.OrbitSettings -> start(ctx, Intent(ctx, SettingsActivity::class.java), mode)
            }
        } catch (_: Exception) {
            false
        }
        if (ok) item.usageKey?.let { Usage.record(it) }
        return ok
    }

    private fun popupOptions(ctx: Context): Bundle {
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels * 0.62f).toInt()
        val h = (dm.heightPixels * 0.72f).toInt()
        val l = (dm.widthPixels - w) / 2
        val t = (dm.heightPixels - h) / 2
        return ActivityOptions.makeBasic().setLaunchBounds(Rect(l, t, l + w, t + h)).toBundle()
    }

    private fun openApp(ctx: Context, item: Item.App, mode: Mode): Boolean {
        val info = item.app.info
        val la = ctx.getSystemService(LauncherApps::class.java)
        if (mode == Mode.SIDE_BY_SIDE && info.user == Process.myUserHandle()) {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(info.componentName)
            return start(ctx, i, mode)
        }
        val opts = if (mode == Mode.POPUP) popupOptions(ctx) else null
        la.startMainActivity(info.componentName, info.user, null, opts)
        return true
    }

    private fun openFile(ctx: Context, item: Item.File, mode: Mode): Boolean {
        val e = item.entry
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, e.mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            start(ctx, view, mode)
        } catch (_: Exception) {
            // No app claims this type: let the user pick one.
            start(ctx, Intent.createChooser(view, e.name), Mode.NORMAL)
        }
    }

    private fun openSetting(ctx: Context, item: Item.Setting, mode: Mode): Boolean {
        val pm = ctx.packageManager
        for (action in item.entry.actions) {
            val i = Intent(action)
            if (pm.resolveActivity(i, 0) != null) return start(ctx, i, mode)
        }
        return false
    }

    private fun openWeb(ctx: Context, query: String, mode: Mode): Boolean {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (ctx.packageManager.resolveActivity(search, 0) != null) return start(ctx, search, mode)
        val url = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
        return start(ctx, Intent(Intent.ACTION_VIEW, url), mode)
    }

    private fun start(ctx: Context, intent: Intent, mode: Mode): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (mode == Mode.SIDE_BY_SIDE) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        ctx.startActivity(intent, if (mode == Mode.POPUP) popupOptions(ctx) else null)
        return true
    }

    private fun copy(ctx: Context, text: String): Boolean {
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("ORBIT", text))
        return true // Android 13+ shows its own "Copied" confirmation.
    }
}
