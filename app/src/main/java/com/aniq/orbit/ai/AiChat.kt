package com.aniq.orbit.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ChatMessage(val fromUser: Boolean, var text: String)

/**
 * One running conversation, kept in memory until you press Ctrl+N (or the app is closed by
 * Android). Answers stream in as server-sent events; no libraries, just HttpURLConnection.
 */
object AiChat {
    val messages = ArrayList<ChatMessage>()
    @Volatile var busy = false
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var generation = 0

    private const val SYSTEM = "You are the assistant inside ORBIT, a quick launcher on a Samsung Galaxy Tab. " +
        "Answer clearly and briefly in plain text. Use short lists only when they help."

    fun clear() {
        cancel()
        messages.clear()
    }

    fun cancel() {
        generation++
        try { conn?.disconnect() } catch (_: Exception) {}
        conn = null
        busy = false
    }

    /**
     * Sends [prompt]. [onUpdate] runs on the main thread whenever the answer grows;
     * [onDone] runs once at the end with an error message, or null when it worked.
     */
    fun send(ctx: Context, prompt: String, onUpdate: () -> Unit, onDone: (String?) -> Unit) {
        if (busy) return
        val app = ctx.applicationContext
        val provider = AiSettings.provider(app)
        val model = AiSettings.model(app, provider)
        val key = KeyVault.load(app, provider)
        val customUrl = AiSettings.customUrl(app)

        val history = messages.map { ChatMessage(it.fromUser, it.text) }
        messages += ChatMessage(true, prompt)
        val answer = ChatMessage(false, "")
        messages += answer
        busy = true
        val myGen = ++generation
        onUpdate()

        Thread {
            val err = try {
                stream(provider, model, key, customUrl, history + ChatMessage(true, prompt)) { piece ->
                    if (generation != myGen) return@stream
                    main.post {
                        if (generation == myGen) {
                            answer.text += piece
                            onUpdate()
                        }
                    }
                }
                null
            } catch (e: Exception) {
                if (generation != myGen) null else friendly(e)
            }
            main.post {
                if (generation != myGen) return@post
                busy = false
                conn = null
                if (err != null && answer.text.isEmpty()) answer.text = "⚠ $err"
                onDone(err)
                onUpdate()
            }
        }.start()
    }

    // ---------- providers ----------

    private fun stream(
        p: AiProvider, model: String, key: String?, customUrl: String,
        msgs: List<ChatMessage>, emit: (String) -> Unit,
    ) {
        if (p != AiProvider.CUSTOM && key.isNullOrBlank()) throw IllegalStateException("Add your ${p.label} API key in ORBIT settings (Ctrl + ,).")
        if (model.isBlank()) throw IllegalStateException("Set a model name in ORBIT settings.")
        when (p) {
            AiProvider.CLAUDE -> {
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", 2048)
                    .put("stream", true)
                    .put("system", SYSTEM)
                    .put("messages", JSONArray().apply {
                        msgs.forEach { put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
                    })
                post(
                    "https://api.anthropic.com/v1/messages", body,
                    mapOf("x-api-key" to key!!, "anthropic-version" to "2023-06-01"),
                ) { json ->
                    if (json.optString("type") == "content_block_delta") {
                        val d = json.optJSONObject("delta")
                        if (d?.optString("type") == "text_delta") emit(d.optString("text"))
                    }
                    if (json.optString("type") == "error") throw IllegalStateException(errorText(json))
                }
            }
            AiProvider.OPENAI -> {
                val body = JSONObject()
                    .put("model", model)
                    .put("stream", true)
                    .put("instructions", SYSTEM)
                    .put("input", JSONArray().apply {
                        msgs.forEach { put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
                    })
                post("https://api.openai.com/v1/responses", body, mapOf("Authorization" to "Bearer $key")) { json ->
                    when (json.optString("type")) {
                        "response.output_text.delta" -> emit(json.optString("delta"))
                        "error", "response.failed" -> throw IllegalStateException(errorText(json))
                    }
                }
            }
            AiProvider.GEMINI -> {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                    .put("contents", JSONArray().apply {
                        msgs.forEach {
                            put(JSONObject()
                                .put("role", if (it.fromUser) "user" else "model")
                                .put("parts", JSONArray().put(JSONObject().put("text", it.text))))
                        }
                    })
                val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                    URLEncoder.encode(model, "UTF-8") + ":streamGenerateContent?alt=sse"
                post(url, body, mapOf("x-goog-api-key" to key!!)) { json ->
                    json.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "Gemini error")) }
                    val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: return@post
                    for (i in 0 until parts.length()) {
                        val o = parts.optJSONObject(i) ?: continue
                        if (!o.optBoolean("thought")) emit(o.optString("text"))
                    }
                }
            }
            AiProvider.OPENROUTER, AiProvider.CUSTOM -> {
                val base = if (p == AiProvider.OPENROUTER) "https://openrouter.ai/api/v1" else customUrl
                if (base.isBlank()) throw IllegalStateException("Add your server address in ORBIT settings.")
                val body = JSONObject()
                    .put("model", model)
                    .put("stream", true)
                    .put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", SYSTEM))
                        msgs.forEach { put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
                    })
                val headers = HashMap<String, String>()
                if (!key.isNullOrBlank()) headers["Authorization"] = "Bearer $key"
                if (p == AiProvider.OPENROUTER) headers["X-Title"] = "ORBIT"
                post("$base/chat/completions", body, headers) { json ->
                    json.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "Server error")) }
                    val c = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return@post
                    val t = c.optString("content", "")
                    if (t.isNotEmpty() && t != "null") emit(t)
                }
            }
        }
    }

    /** POSTs JSON and feeds each server-sent `data:` line to [onEvent] as it arrives. */
    private fun post(url: String, body: JSONObject, headers: Map<String, String>, onEvent: (JSONObject) -> Unit) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn = c
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) {
            val raw = try { c.errorStream?.bufferedReader()?.readText().orEmpty() } catch (_: Exception) { "" }
            val msg = try { errorText(JSONObject(raw)) } catch (_: Exception) { raw.take(200) }
            throw IllegalStateException(when (code) {
                401, 403 -> "The API key was refused ($code). Check it in ORBIT settings."
                404 -> "Model or address not found ($code). Check the model name in ORBIT settings. $msg"
                429 -> "Rate limit or out of credit ($code). $msg"
                else -> "Error $code. $msg"
            }.trim())
        }
        BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val json = try { JSONObject(data) } catch (_: Exception) { continue }
                onEvent(json)
            }
        }
        c.disconnect()
    }

    private fun errorText(json: JSONObject): String {
        json.optJSONObject("error")?.let { return it.optString("message", it.toString()) }
        json.optJSONObject("response")?.optJSONObject("error")?.let { return it.optString("message") }
        return json.optString("message", "Something went wrong")
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection."
        is java.net.SocketTimeoutException -> "The AI took too long to answer. Try again."
        is java.net.ConnectException -> "Couldn't reach the server."
        else -> e.message ?: "Something went wrong."
    }
}
