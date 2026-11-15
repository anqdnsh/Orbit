package com.aniq.orbit.tools

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * "dex" + Enter: enters or exits Samsung DeX in one go.
 *
 * Android gives apps no switch for DeX, so this does what you'd do by hand: open Quick
 * Settings, find the DeX tile, tap it, then tuck the shade away. It only reads the
 * Quick Settings panel, and only while this runs.
 */
object DexSwitch {
    private val main = Handler(Looper.getMainLooper())
    private const val SYSTEM_UI = "com.android.systemui"
    private val DEX = Regex("\\bDeX\\b")
    private val CONFIRM = Regex("^(Start|Switch|Continue|OK|Turn on|Start DeX|Exit|Exit DeX)$", RegexOption.IGNORE_CASE)

    fun toggle(svc: AccessibilityService) {
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        // Give the shade a moment to slide open, then look for the tile.
        attempt(svc, tries = 0, scrolled = false)
    }

    private fun attempt(svc: AccessibilityService, tries: Int, scrolled: Boolean) {
        main.postDelayed({
            val tile = findTile(svc)
            when {
                tile != null -> {
                    val target = clickableOf(tile)
                    if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                        confirm(svc, 0)
                    } else {
                        toast(svc, "Tap the DeX tile to switch")
                    }
                }
                tries < 8 -> attempt(svc, tries + 1, scrolled)
                !scrolled && scrollTiles(svc) -> attempt(svc, 0, scrolled = true)
                else -> toast(svc, "Couldn't find the DeX tile. Add it to Quick Settings (✎ Edit), then try again")
            }
        }, if (tries == 0) 380L else 140L)
    }

    /** Some tablets ask once before switching; press the go-ahead button, then close the shade. */
    private fun confirm(svc: AccessibilityService, tries: Int) {
        main.postDelayed({
            val btn = svc.windows.asSequence()
                .mapNotNull { it.root }
                .filter { it.packageName?.toString().let { p -> p == SYSTEM_UI || p?.contains("desktopmode") == true } }
                .flatMap { walk(it) }
                .firstOrNull { n ->
                    n.isClickable && n.className?.toString()?.endsWith("Button") == true &&
                        CONFIRM.matches(n.text?.toString()?.trim().orEmpty())
                }
            when {
                btn != null -> { btn.performAction(AccessibilityNodeInfo.ACTION_CLICK); dismissShade(svc) }
                tries < 6 -> confirm(svc, tries + 1)
                else -> dismissShade(svc)
            }
        }, 250L)
    }

    private fun dismissShade(svc: AccessibilityService) {
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    private fun shadeRoots(svc: AccessibilityService): Sequence<AccessibilityNodeInfo> =
        svc.windows.asSequence().mapNotNull { it.root }.filter { it.packageName?.toString() == SYSTEM_UI }

    private fun findTile(svc: AccessibilityService): AccessibilityNodeInfo? =
        shadeRoots(svc).flatMap { walk(it) }.firstOrNull { n ->
            val label = (n.contentDescription ?: n.text)?.toString().orEmpty()
            DEX.containsMatchIn(label) && n.isVisibleToUser && looksLikeTile(n)
        }

    /** A Quick Settings tile, not a "DeX is running" notification that also says DeX. */
    private fun looksLikeTile(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 6) {
            val id = cur.viewIdResourceName.orEmpty()
            if (id.contains("notification", true)) return false
            val cls = cur.className?.toString().orEmpty()
            if (id.contains("tile", true) || cls.endsWith("Switch") || cls.endsWith("ToggleButton") || cls.endsWith("Button")) return true
            cur = cur.parent
            hops++
        }
        return false
    }

    /** The DeX tile might be on the next Quick Settings page. */
    private fun scrollTiles(svc: AccessibilityService): Boolean =
        shadeRoots(svc).flatMap { walk(it) }.firstOrNull { it.isScrollable && it.isVisibleToUser }
            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 5) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    private fun walk(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 1500) {
            val n = stack.removeLast()
            seen++
            yield(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
    }

    private fun toast(svc: AccessibilityService, msg: String) =
        Toast.makeText(svc, msg, Toast.LENGTH_LONG).show()
}
