package com.aniq.orbit.search

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.util.concurrent.Executors

/** All launchable apps (including a work profile), kept in memory and refreshed on install/uninstall. */
object AppIndex {
    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private lateinit var appCtx: Context
    private lateinit var launcherApps: LauncherApps

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        launcherApps = appCtx.getSystemService(LauncherApps::class.java)
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reload()
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = reload()
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = reload()
            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
        }, main)
        reload()
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun reload() {
        worker.execute {
            val me = Process.myUserHandle()
            val users = appCtx.getSystemService(UserManager::class.java).userProfiles
            val out = ArrayList<AppEntry>(256)
            for (user in users) {
                val list = try { launcherApps.getActivityList(null, user) } catch (_: Exception) { emptyList() }
                for (info in list) {
                    if (info.componentName.packageName == appCtx.packageName) continue
                    val label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName }
                    val key = "app:" + info.componentName.flattenToShortString() + "@" + user.hashCode()
                    out += AppEntry(label, Text.normalize(label), Text.initials(label), info, key, user != me)
                }
            }
            out.sortBy { it.norm }
            apps = out
            IconLoader.prewarm(out.sortedByDescending { Usage.count(it.key) }.take(16))
            main.post { listeners.toList().forEach { it() } }
        }
    }
}
