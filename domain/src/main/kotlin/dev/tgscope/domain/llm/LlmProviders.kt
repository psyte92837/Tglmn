package dev.tgscope.domain.llm

/** Wire protocol of a provider. Almost everyone speaks the OpenAI chat-completions dialect. */
enum class ApiStyle { OPENAI, ANTHROPIC }

data class LlmProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** Where the user creates an API key; null for local servers. */
    val keyUrl: String?,
    val style: ApiStyle = ApiStyle.OPENAI,
    val needsKey: Boolean = true,
    /** Examples only: the app can load the live model list from the provider. */
    val modelHints: List<String> = emptyList(),
    val note: String = "",
)

object LlmProviders {
    val all: List<LlmProvider> = listOf(
        LlmProvider(
            id = "openai",
            name = "OpenAI (ChatGPT)",
            baseUrl = "https://api.openai.com/v1",
            keyUrl = "https://platform.openai.com/api-keys",
            modelHints = listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o"),
        ),
        LlmProvider(
            id = "anthropic",
            name = "Anthropic (Claude)",
            baseUrl = "https://api.anthropic.com/v1",
            keyUrl = "https://platform.claude.com/settings/keys",
            style = ApiStyle.ANTHROPIC,
            modelHints = listOf("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5"),
        ),
        LlmProvider(
            id = "gemini",
            name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            keyUrl = "https://aistudio.google.com/app/apikey",
            modelHints = listOf("gemini-2.5-flash", "gemini-2.5-pro"),
            note = "Есть бесплатный лимит в Google AI Studio.",
        ),
        LlmProvider(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            keyUrl = "https://platform.deepseek.com/api_keys",
            modelHints = listOf("deepseek-chat", "deepseek-reasoner"),
        ),
        LlmProvider(
            id = "mistral",
            name = "Mistral AI",
            baseUrl = "https://api.mistral.ai/v1",
            keyUrl = "https://console.mistral.ai/api-keys",
            modelHints = listOf("mistral-small-latest", "mistral-large-latest"),
        ),
        LlmProvider(
            id = "xai",
            name = "xAI (Grok)",
            baseUrl = "https://api.x.ai/v1",
            keyUrl = "https://console.x.ai",
        ),
        LlmProvider(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            keyUrl = "https://console.groq.com/keys",
            modelHints = listOf("llama-3.3-70b-versatile"),
            note = "Очень быстрые открытые модели, есть бесплатный лимит.",
        ),
        LlmProvider(
            id = "openrouter",
            name = "OpenRouter (сотни моделей)",
            baseUrl = "https://openrouter.ai/api/v1",
            keyUrl = "https://openrouter.ai/keys",
            modelHints = listOf("openai/gpt-4o-mini", "anthropic/claude-sonnet-5-5", "google/gemini-2.5-flash"),
            note = "Один ключ для моделей разных компаний.",
        ),
        LlmProvider(
            id = "perplexity",
            name = "Perplexity",
            baseUrl = "https://api.perplexity.ai",
            keyUrl = "https://www.perplexity.ai/settings/api",
            modelHints = listOf("sonar", "sonar-pro"),
        ),
        LlmProvider(
            id = "ollama",
            name = "Ollama (свой компьютер)",
            baseUrl = "http://192.168.1.10:11434/v1",
            keyUrl = "https://ollama.com/download",
            needsKey = false,
            note = "Полностью локально: замените IP на адрес компьютера с Ollama в вашей сети (OLLAMA_HOST=0.0.0.0).",
        ),
        LlmProvider(
            id = "lmstudio",
            name = "LM Studio (свой компьютер)",
            baseUrl = "http://192.168.1.10:1234/v1",
            keyUrl = "https://lmstudio.ai",
            needsKey = false,
            note = "Включите в LM Studio «Serve on local network» и укажите IP компьютера.",
        ),
        LlmProvider(
            id = "custom",
            name = "Другой (OpenAI-совместимый)",
            baseUrl = "",
            keyUrl = null,
            needsKey = false,
            note = "Любой сервис с API в формате OpenAI /chat/completions.",
        ),
    )

    fun byId(id: String?): LlmProvider = all.firstOrNull { it.id == id } ?: all.last()
}
