package dev.tgscope.domain.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LlmException(message: String) : Exception(message)

data class LlmConfig(
    val style: ApiStyle,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val maxTokens: Int = 2048,
    val temperature: Double = 0.3,
)

/** Minimal client for the OpenAI chat-completions dialect and Anthropic's Messages API. */
class LlmClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    suspend fun complete(cfg: LlmConfig, system: String, user: String): String = withContext(Dispatchers.IO) {
        when (cfg.style) {
            ApiStyle.OPENAI -> {
                val body = buildJsonObject {
                    put("model", cfg.model)
                    put("temperature", cfg.temperature)
                    put("max_tokens", cfg.maxTokens)
                    put(
                        "messages",
                        buildJsonArray {
                            add(buildJsonObject { put("role", "system"); put("content", system) })
                            add(buildJsonObject { put("role", "user"); put("content", user) })
                        },
                    )
                }
                val response = try {
                    post(cfg, chatUrl(cfg.endpoint), body)
                } catch (e: LlmException) {
                    // Newer reasoning models reject max_tokens / temperature: retry with the bare minimum.
                    val msg = e.message.orEmpty()
                    if (msg.startsWith("HTTP 400") && ("max_tokens" in msg || "temperature" in msg)) {
                        post(cfg, chatUrl(cfg.endpoint), JsonObject(body.filterKeys { it == "model" || it == "messages" }))
                    } else {
                        throw e
                    }
                }
                response["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw LlmException("Модель вернула пустой ответ")
            }
            ApiStyle.ANTHROPIC -> {
                val body = buildJsonObject {
                    put("model", cfg.model)
                    put("max_tokens", cfg.maxTokens)
                    put("temperature", cfg.temperature)
                    put("system", system)
                    put(
                        "messages",
                        buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", user) }) },
                    )
                }
                val response = post(cfg, base(cfg.endpoint) + "/messages", body)
                (response["content"] as? JsonArray).orEmpty()
                    .mapNotNull { block ->
                        val obj = block.jsonObject
                        if (obj["type"]?.jsonPrimitive?.content == "text") obj["text"]?.jsonPrimitive?.content else null
                    }
                    .joinToString("\n").trim().takeIf { it.isNotEmpty() }
                    ?: throw LlmException("Модель вернула пустой ответ")
            }
        }
    }

    /** Live model list (`GET /models`), so the app never depends on hardcoded model names. */
    suspend fun listModels(cfg: LlmConfig): List<String> = withContext(Dispatchers.IO) {
        val url = base(cfg.endpoint) + "/models" + if (cfg.style == ApiStyle.ANTHROPIC) "?limit=1000" else ""
        val request = Request.Builder().url(url).get().auth(cfg).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmException(httpError(response.code, text))
            val root = runCatching { json.parseToJsonElement(text) }.getOrElse { throw LlmException("Непонятный ответ: ${text.take(200)}") }
            val items = when {
                root is JsonArray -> root
                root is JsonObject && root["data"] is JsonArray -> root["data"]!!.jsonArray
                root is JsonObject && root["models"] is JsonArray -> root["models"]!!.jsonArray
                else -> JsonArray(emptyList())
            }
            items.mapNotNull { item ->
                (item as? JsonObject)?.let { it["id"] ?: it["name"] }?.jsonPrimitive?.content?.removePrefix("models/")
            }.distinct().sorted()
        }
    }

    private fun post(cfg: LlmConfig, url: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonType))
            .auth(cfg)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmException(httpError(response.code, text))
            return runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw LlmException("Непонятный ответ: ${text.take(300)}") }
        }
    }

    private fun Request.Builder.auth(cfg: LlmConfig): Request.Builder = apply {
        when (cfg.style) {
            ApiStyle.OPENAI -> {
                if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}")
                // Optional attribution headers used by OpenRouter; ignored elsewhere.
                header("X-Title", "TGScope")
            }
            ApiStyle.ANTHROPIC -> {
                header("x-api-key", cfg.apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
            }
        }
    }

    private fun httpError(code: Int, body: String): String {
        val hint = when (code) {
            401, 403 -> "неверный или отозванный API-ключ"
            404 -> "неверный адрес или модель"
            429 -> "превышен лимит запросов или закончились средства"
            in 500..599 -> "ошибка на стороне провайдера"
            else -> null
        }
        return "HTTP $code" + (hint?.let { " ($it)" } ?: "") + ": " + body.take(300)
    }

    companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"

        fun base(endpoint: String): String = endpoint.trim().trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/messages")

        fun chatUrl(endpoint: String): String = base(endpoint) + "/chat/completions"
    }
}
