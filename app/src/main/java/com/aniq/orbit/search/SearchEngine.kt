package com.aniq.orbit.search

import com.aniq.orbit.Feature
import android.content.Context
import com.aniq.orbit.CommandEntry
import com.aniq.orbit.Commands

object SearchEngine {
    const val MAX = 8

    /**
     * Ranked results. Index 0 is always the best match, so Enter opens it.
     * Order: calculator answer or "volume 40"-style command → apps / system / settings by score → web search.
     */
    /** Time the last search took, in microseconds (shown in ORBIT settings › Performance). */
    @Volatile var lastSearchMicros = 0L
        private set

    /**
     * Ranked results. Index 0 is always the best match, so Enter opens it.
     * Order: calculator / currency / typed command → apps, system, settings
     * and files by score → web search. [onAsync] runs when slower parts (files,
     * currency rates) arrive, so the caller can refresh.
     */
    fun search(ctx: Context, raw: String, filter: Filter, onAsync: () -> Unit = {}): List<Item> {
        val t0 = System.nanoTime()
        val r = searchInner(ctx, raw, filter, onAsync)
        lastSearchMicros = (System.nanoTime() - t0) / 1000
        return r
    }

    private fun searchInner(ctx: Context, raw: String, filter: Filter, onAsync: () -> Unit): List<Item> {
        val q = Text.normalize(raw)
        val trimmed = raw.trim()
        if (filter == Filter.FILES) return if (Feature.FILES.on(ctx)) files(ctx, trimmed, onAsync, MAX) else emptyList()
        if (raw.isBlank()) return suggestions(ctx, filter)

        val out = ArrayList<Item>(MAX)
        if (filter == Filter.ALL) {
            if (Feature.CALC.on(ctx)) Calc.evaluate(raw)?.let { out += Item.Calc(trimmed, it) }
            if (Feature.CURRENCY.on(ctx)) Currency.parse(raw)?.let { out += money(ctx, it, onAsync) }
        }
        if (filter == Filter.ALL || filter == Filter.SYSTEM) {
            Commands.parametric(raw)?.let { (e, v) -> if (Feature.commandOn(ctx, e.id)) out += command(ctx, e, v) }
        }

        // Score first, build result rows only for the winners (commands read live device state).
        val scored = ArrayList<Pair<() -> Item, Int>>()
        fun put(score: Int, make: () -> Item) { scored.add(Pair(make, score)) }
        if (filter == Filter.ALL || filter == Filter.APPS) {
            for (a in AppIndex.apps) {
                val s = Matcher.score(q, a.norm, a.initials)
                if (s >= 0) put(s + Usage.boost(a.key)) { Item.App(a) }
            }
        }
        if (filter == Filter.ALL || filter == Filter.SYSTEM) {
            for (e in Commands.entries) {
                if (!Feature.commandOn(ctx, e.id)) continue
                var s = Matcher.score(q, e.norm, e.initials)
                for (k in e.keywordNorms) s = maxOf(s, Matcher.score(q, k, "") - 40)
                // Typing a command's exact name ("dex", "restart") puts it first, so Enter runs it.
                if (s >= 0 && (q == e.norm || q in e.keywordNorms)) s += 1000
                if (s >= 0) put(s - 20 + Usage.boost("cmd:" + e.id)) { command(ctx, e, null) }
            }
        }
        if (filter == Filter.ALL || filter == Filter.SETTINGS) {
            if (Feature.SETTINGS_PAGES.on(ctx)) for (e in SettingsCatalog.entries) {
                var s = Matcher.score(q, e.norm, e.initials)
                for (k in e.keywordNorms) s = maxOf(s, Matcher.score(q, k, "") - 60)
                // Small penalty so an app or command wins a tie with a settings page of the same name.
                if (s >= 0) put(s - 40 + Usage.boost("set:" + e.id)) { Item.Setting(e) }
            }
            val o = Item.OrbitSettings
            var s = Matcher.score(q, o.norm, "os")
            for (k in o.keywords) s = maxOf(s, Matcher.score(q, k, "") - 60)
            if (s >= 0) put(s - 60) { o }
        }

        scored.sortByDescending { it.second }
        val fileRows = if (filter == Filter.ALL && Feature.FILES.on(ctx)) files(ctx, trimmed, onAsync, 3) else emptyList()
        val web = filter == Filter.ALL && Feature.WEB.on(ctx)
        val tail = if (web) 1 else 0
        val room = MAX - out.size - tail - fileRows.size
        scored.take(room.coerceAtLeast(0)).forEach { out += it.first() }
        out += fileRows
        if (web) out += Item.Web(trimmed)
        return out.take(MAX)
    }

    private fun files(ctx: Context, q: String, onAsync: () -> Unit, limit: Int): List<Item> {
        if (!FileSearch.allowed()) {
            return if (limit == MAX) listOf(Item.Permission(
                "Allow file access to search files",
                "Android's \"All files access\" lets OrbitSearch search names of your documents, PDFs and downloads",
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            )) else emptyList()
        }
        if (q.length < 2) return emptyList()
        val hit = FileSearch.cached(q)
        if (hit == null) { FileSearch.request(ctx, q, onAsync); return emptyList() }
        return hit.take(limit).map { Item.File(it, it.describe(ctx) + "  ·  Space preview") }
    }

    private fun money(ctx: Context, m: Currency.Query, onAsync: () -> Unit): Item {
        val amt = if (m.amount % 1.0 == 0.0) m.amount.toLong().toString() else m.amount.toString()
        val rates = Currency.rates(ctx, m.from, onAsync)
            ?: return Item.Money(m, null, "Converting $amt ${m.from} → ${m.to}…", "Getting today's rates (once a day, then cached)")
        val rate = rates.rates[m.to]
            ?: return Item.Money(m, null, "No ${m.to} rate available", "${rates.source}, ${rates.date}")
        val v = m.amount * rate
        return Item.Money(
            m, v, Currency.format(v, m.to),
            "$amt ${m.from} → ${m.to}  ·  1 ${m.from} = ${"%.4f".format(rate)} ${m.to}  ·  ${rates.date}  ·  ${rates.source}",
        )
    }

    private fun command(ctx: Context, e: CommandEntry, v: Int?) =
        Item.Command(e, v, Commands.title(ctx, e, v), Commands.status(ctx, e))

    private fun suggestions(ctx: Context, filter: Filter): List<Item> = when (filter) {
        Filter.SETTINGS -> SettingsCatalog.entries
            .filter { Feature.SETTINGS_PAGES.on(ctx) }
            .sortedByDescending { Usage.boost("set:" + it.id) }
            .take(MAX - 1)
            .map<SettingEntry, Item> { Item.Setting(it) } + Item.OrbitSettings
        Filter.SYSTEM -> Commands.entries
            .filter { Feature.commandOn(ctx, it.id) }
            .sortedByDescending { Usage.boost("cmd:" + it.id) }
            .take(MAX)
            .map { command(ctx, it, null) }
        else -> AppIndex.apps
            .sortedWith(compareByDescending<AppEntry> { Usage.boost(it.key) }.thenBy { it.norm })
            .take(MAX)
            .map { Item.App(it, suggested = Usage.count(it.key) > 0) }
    }
}
