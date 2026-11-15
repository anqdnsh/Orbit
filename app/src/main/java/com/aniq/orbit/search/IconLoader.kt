package com.aniq.orbit.search

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Loads app icons off the main thread and keeps recent ones in memory. */
object IconLoader {
    private val cache = LruCache<String, Drawable.ConstantState>(200)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun load(app: AppEntry, into: ImageView) {
        into.tag = app.key
        val hit = cache.get(app.key)
        if (hit != null) {
            into.setImageDrawable(hit.newDrawable())
            return
        }
        into.setImageDrawable(null)
        worker.execute {
            val state = fetch(app) ?: return@execute
            main.post { if (into.tag == app.key) into.setImageDrawable(state.newDrawable()) }
        }
    }

    /** Drop cached icons when Android needs memory back. */
    fun trim() = cache.evictAll()

    fun prewarm(apps: List<AppEntry>) {
        worker.execute { apps.forEach { if (cache.get(it.key) == null) fetch(it) } }
    }

    private fun fetch(app: AppEntry): Drawable.ConstantState? {
        cache.get(app.key)?.let { return it }
        val d = try { app.info.getBadgedIcon(0) } catch (_: Exception) { null } ?: return null
        val state = d.constantState ?: return null
        cache.put(app.key, state)
        return state
    }
}
