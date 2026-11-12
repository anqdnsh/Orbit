package com.aniq.orbit.search

import android.content.Context
import com.aniq.orbit.CommandEntry
import com.aniq.orbit.Commands

object SearchEngine {
    const val MAX = 8

    /**
     * Ranked results. Index 0 is always the best match, so Enter opens it.
     * Order: calculator answer or "volume 40"-style command → apps / system / settings by score → web search.
     */
    fun search(ctx: Context, raw: String, filter: Filter): List<Item> {
        val q = Text.normalize(raw)
        if (raw.isBlank()) return suggestions(ctx, filter)

        val out = ArrayList<Item>(MAX)
        if (filter == Filter.ALL) Calc.evaluate(raw)?.let { out += Item.Calc(raw.trim(), it) }
        if (filter == Filter.ALL || filter == Filter.SYSTEM) {
            Commands.parametric(raw)?.let { (e, v) -> out += command(ctx, e, v) }
        }

        val scored = ArrayList<Pair<Item, Int>>()
        if (filter == Filter.ALL || filter == Filter.APPS) {
            for (a in AppIndex.apps) {
                val s = Matcher.score(q, a.norm, a.initials)
                if (s >= 0) scored += Item.App(a) to s + Usage.boost(a.key)
            }
        }
        if (filter == Filter.ALL || filter == Filter.SYSTEM) {
            for (e in Commands.entries) {
                var s = Matcher.score(q, e.norm, e.initials)
                for (k in e.keywordNorms) s = maxOf(s, Matcher.score(q, k, "") - 40)
                if (s >= 0) scored += command(ctx, e, null) to s - 20 + Usage.boost("cmd:" + e.id)
            }
        }
        if (filter == Filter.ALL || filter == Filter.SETTINGS) {
            for (e in SettingsCatalog.entries) {
                var s = Matcher.score(q, e.norm, e.initials)
                for (k in e.keywordNorms) s = maxOf(s, Matcher.score(q, k, "") - 60)
                // Small penalty so an app or command wins a tie with a settings page of the same name.
                if (s >= 0) scored += Item.Setting(e) to s - 40 + Usage.boost("set:" + e.id)
            }
            val o = Item.OrbitSettings
            var s = Matcher.score(q, o.norm, "os")
            for (k in o.keywords) s = maxOf(s, Matcher.score(q, k, "") - 60)
            if (s >= 0) scored += o to s - 60
        }

        scored.sortByDescending { it.second }
        val room = MAX - out.size - (if (filter == Filter.ALL) 1 else 0)
        scored.take(room.coerceAtLeast(0)).forEach { out += it.first }
        if (filter == Filter.ALL) out += Item.Web(raw.trim())
        return out
    }

    private fun command(ctx: Context, e: CommandEntry, v: Int?) = Item.Command(e, v, Commands.status(ctx, e))

    private fun suggestions(ctx: Context, filter: Filter): List<Item> = when (filter) {
        Filter.SETTINGS -> SettingsCatalog.entries
            .sortedByDescending { Usage.boost("set:" + it.id) }
            .take(MAX - 1)
            .map<SettingEntry, Item> { Item.Setting(it) } + Item.OrbitSettings
        Filter.SYSTEM -> Commands.entries
            .sortedByDescending { Usage.boost("cmd:" + it.id) }
            .take(MAX)
            .map { command(ctx, it, null) }
        else -> AppIndex.apps
            .sortedWith(compareByDescending<AppEntry> { Usage.boost(it.key) }.thenBy { it.norm })
            .take(MAX)
            .map { Item.App(it, suggested = Usage.count(it.key) > 0) }
    }
}
