package com.aniq.orbit.search

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "100 usd to myr", "rm50 in sgd", "250 eur". Rates come from Frankfurter (official central-bank
 * reference rates, no key), with ExchangeRate-API's open endpoint as a backup. They are fetched only
 * when you convert, then kept for 12 hours, so it works offline with the last rates.
 */
object Currency {
    class Query(val amount: Double, val from: String, val to: String)
    class Rates(val base: String, val date: String, val source: String, val rates: Map<String, Double>, val fetchedAt: Long)

    private val symbols = linkedMapOf(
        "us$" to "USD", "s$" to "SGD", "a$" to "AUD", "c$" to "CAD", "hk$" to "HKD", "nz$" to "NZD",
        "rm" to "MYR", "$" to "USD", "€" to "EUR", "£" to "GBP", "¥" to "JPY", "₹" to "INR",
        "฿" to "THB", "₩" to "KRW", "rp" to "IDR", "₱" to "PHP",
    )
    private val words = mapOf(
        "ringgit" to "MYR", "dollar" to "USD", "dollars" to "USD", "euro" to "EUR", "euros" to "EUR",
        "pound" to "GBP", "pounds" to "GBP", "yen" to "JPY", "rupee" to "INR", "rupees" to "INR",
        "baht" to "THB", "won" to "KRW", "yuan" to "CNY", "rupiah" to "IDR", "rm" to "MYR",
    )
    private val unit = "([a-z]{3}|ringgit|dollars?|euros?|pounds?|yen|rupees?|baht|won|yuan|rupiah|rm)"
    private val pattern = Regex(
        "^(us\\$|s\\$|a\\$|c\\$|hk\\$|nz\\$|rm|\\$|€|£|¥|₹|฿|₩|rp|₱)?\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*$unit?" +
            "\\s*(?:(?:to|in|into|->|=)\\s*$unit)?\\s*$"
    )

    private fun iso(code: String?): String? {
        if (code == null) return null
        val c = words[code] ?: code.uppercase(Locale.ROOT)
        return try { java.util.Currency.getInstance(c).currencyCode } catch (_: Exception) { null }
    }

    private fun localCurrency(): String = try {
        java.util.Currency.getInstance(Locale.getDefault()).currencyCode
    } catch (_: Exception) { "MYR" }

    fun parse(raw: String): Query? {
        val m = pattern.find(raw.trim().lowercase(Locale.ROOT)) ?: return null
        val (sym, num, fromWord, toWord) = m.destructured
        val from = (if (sym.isNotEmpty()) symbols[sym] else null) ?: iso(fromWord.ifEmpty { null }) ?: return null
        if (sym.isEmpty() && fromWord.isEmpty()) return null
        val amount = num.replace(",", "").toDoubleOrNull() ?: return null
        val to = iso(toWord.ifEmpty { null }) ?: localCurrency().let { if (it == from) (if (from == "USD") "MYR" else "USD") else it }
        if (to == from) return null
        return Query(amount, from, to)
    }

    // ---------- rates ----------

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val memory = HashMap<String, Rates>()
    private val inFlight = HashSet<String>()
    private const val TTL = 12 * 3_600_000L

    /** Cached rates for [base], or null (and a fetch starts; [ready] runs when it lands). */
    fun rates(ctx: Context, base: String, ready: () -> Unit): Rates? {
        val hit = memory[base] ?: load(ctx, base)?.also { memory[base] = it }
        if (hit == null || System.currentTimeMillis() - hit.fetchedAt > TTL) fetch(ctx, base, ready)
        return hit
    }

    fun format(value: Double, code: String): String {
        val nf = NumberFormat.getCurrencyInstance(Locale.getDefault())
        nf.currency = java.util.Currency.getInstance(code)
        nf.maximumFractionDigits = if (value < 1) 4 else 2
        return nf.format(value)
    }

    private fun fetch(ctx: Context, base: String, ready: () -> Unit) {
        synchronized(inFlight) { if (!inFlight.add(base)) return }
        val app = ctx.applicationContext
        worker.execute {
            val r = tryFrankfurter(base) ?: tryErApi(base)
            main.post {
                synchronized(inFlight) { inFlight.remove(base) }
                if (r != null) {
                    memory[base] = r
                    save(app, r)
                    ready()
                }
            }
        }
    }

    private fun get(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode in 200..299) JSONObject(c.inputStream.bufferedReader().readText()) else null
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun tryFrankfurter(base: String): Rates? {
        val j = get("https://api.frankfurter.dev/v1/latest?from=$base") ?: return null
        val r = j.optJSONObject("rates") ?: return null
        return Rates(base, j.optString("date"), "Frankfurter", toMap(r), System.currentTimeMillis())
    }

    private fun tryErApi(base: String): Rates? {
        val j = get("https://open.er-api.com/v6/latest/$base") ?: return null
        if (j.optString("result") != "success") return null
        val r = j.optJSONObject("rates") ?: return null
        val date = j.optString("time_last_update_utc").split(' ').take(4).joinToString(" ")
        return Rates(base, date, "Rates by ExchangeRate-API", toMap(r), System.currentTimeMillis())
    }

    private fun toMap(o: JSONObject): Map<String, Double> {
        val m = HashMap<String, Double>()
        o.keys().forEach { k -> m[k] = o.optDouble(k) }
        return m
    }

    private fun save(ctx: Context, r: Rates) {
        val j = JSONObject().put("date", r.date).put("source", r.source).put("at", r.fetchedAt)
            .put("rates", JSONObject(r.rates as Map<*, *>))
        ctx.getSharedPreferences("rates", Context.MODE_PRIVATE).edit().putString(r.base, j.toString()).apply()
    }

    private fun load(ctx: Context, base: String): Rates? = try {
        ctx.getSharedPreferences("rates", Context.MODE_PRIVATE).getString(base, null)?.let {
            val j = JSONObject(it)
            Rates(base, j.optString("date"), j.optString("source"), toMap(j.getJSONObject("rates")), j.optLong("at"))
        }
    } catch (_: Exception) { null }
}

/** Last few calculator and currency answers you copied, shown on the calculator card. */
object CalcHistory {
    fun list(ctx: Context): List<Pair<String, String>> =
        ctx.getSharedPreferences("calc", Context.MODE_PRIVATE).getString("recent", "")!!
            .split('\n').filter { it.contains('\t') }.map { it.substringBefore('\t') to it.substringAfter('\t') }

    fun add(ctx: Context, expr: String, value: String) {
        val now = (listOf(expr to value) + list(ctx).filter { it.first != expr }).take(5)
        ctx.getSharedPreferences("calc", Context.MODE_PRIVATE).edit()
            .putString("recent", now.joinToString("\n") { "${it.first}\t${it.second}" }).apply()
    }
}
