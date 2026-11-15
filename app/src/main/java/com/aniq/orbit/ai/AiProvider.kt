package com.aniq.orbit.ai

import android.content.Context
import com.aniq.orbit.Prefs

/**
 * AI providers you can connect with your own API key. Model names change often, so each one
 * is editable in ORBIT settings; these are just starting points (checked Nov 2026).
 */
enum class AiProvider(val id: String, val label: String, val defaultModel: String, val keyUrl: String) {
    CLAUDE("anthropic", "Claude (Anthropic)", "claude-sonnet-5-5", "console.anthropic.com"),
    OPENAI("openai", "ChatGPT (OpenAI)", "gpt-6-luna", "platform.openai.com"),
    GEMINI("gemini", "Gemini (Google)", "gemini-flash-latest", "aistudio.google.com"),
    OPENROUTER("openrouter", "OpenRouter (many models, one key)", "openrouter/auto", "openrouter.ai/keys"),
    CUSTOM("custom", "Custom server (OpenAI-compatible)", "", "your server");

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: CLAUDE
    }
}

/** Which provider, model and server ORBIT uses. The key itself lives in [KeyVault]. */
object AiSettings {
    fun provider(ctx: Context): AiProvider = AiProvider.fromId(Prefs.of(ctx).getString("ai_provider", null))
    fun setProvider(ctx: Context, p: AiProvider) = Prefs.of(ctx).edit().putString("ai_provider", p.id).apply()

    fun model(ctx: Context, p: AiProvider): String =
        Prefs.of(ctx).getString("ai_model_" + p.id, null)?.takeIf { it.isNotBlank() } ?: p.defaultModel

    fun setModel(ctx: Context, p: AiProvider, m: String) =
        Prefs.of(ctx).edit().putString("ai_model_" + p.id, m.trim()).apply()

    /** Base URL for the custom server, e.g. http://192.168.1.20:11434/v1 for Ollama on a PC. */
    fun customUrl(ctx: Context): String = Prefs.of(ctx).getString("ai_custom_url", "") ?: ""
    fun setCustomUrl(ctx: Context, u: String) = Prefs.of(ctx).edit().putString("ai_custom_url", u.trim().trimEnd('/')).apply()

    /** True when ORBIT has what it needs to send a question. */
    fun ready(ctx: Context): Boolean {
        val p = provider(ctx)
        return when (p) {
            AiProvider.CUSTOM -> customUrl(ctx).isNotBlank() && model(ctx, p).isNotBlank()
            else -> KeyVault.has(ctx, p)
        }
    }
}
