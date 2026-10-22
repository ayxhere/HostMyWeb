package com.imayx.host.ai

import com.imayx.host.security.SecretStore

/**
 * The AI providers the fixer can use. All three speak the OpenAI chat-completions
 * format, so a single client ([AiFixer]) serves all of them — only the base URL,
 * key and model list differ.
 *
 * Each provider has:
 *  - its own API key in the Keystore-backed [SecretStore] ([secretKeyName]),
 *  - its own selected model in AppSettings (AppSettings.aiModelFor),
 *  - an ordered list of [defaultModels] used as failover candidates and by the
 *    Settings "Test" action (the first model that answers is auto-selected).
 */
enum class AiProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val secretKeyName: String,
    val defaultModels: List<String>,
    /** Where the user gets a key (shown as a hint on the provider's Settings page). */
    val keyHint: String
) {
    GROQ(
        "groq", "Groq",
        "https://api.groq.com/openai/v1",
        SecretStore.KEY_GROQ_API_KEY,
        listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b"),
        "console.groq.com"
    ),
    GOOGLE(
        "google", "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        SecretStore.KEY_GOOGLE_API_KEY,
        listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.0-flash-lite"),
        "aistudio.google.com"
    ),
    OPENROUTER(
        "openrouter", "OpenRouter",
        "https://openrouter.ai/api/v1",
        SecretStore.KEY_OPENROUTER_API_KEY,
        listOf("openai/gpt-oss-120b", "meta-llama/llama-3.3-70b-instruct"),
        "openrouter.ai/keys"
    );

    val chatUrl: String get() = "$baseUrl/chat/completions"

    companion object {
        fun byId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: GROQ
    }
}
