package dev.tgscope.domain.llm

import dev.tgscope.core.database.AnalysisEntity
import dev.tgscope.core.database.TgDatabase
import dev.tgscope.core.settings.AppSettings
import dev.tgscope.core.settings.SettingsRepository
import dev.tgscope.domain.export.Anonymizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class LlmException(message: String) : Exception(message)

/**
 * Analyzes stored discussions with any OpenAI-compatible chat completions endpoint
 * (OpenAI, OpenRouter, a local Ollama / LM Studio on the LAN, ...). Long discussions are
 * split into chunks, analyzed separately and merged (map-reduce).
 *
 * Author names are replaced with stable aliases before anything leaves the device.
 */
class LlmAnalyzer(
    private val db: TgDatabase,
    private val settings: SettingsRepository,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun analyzePost(chatId: Long, postId: Long, promptName: String): AnalysisEntity {
        val s = requireConfigured()
        settings.ensureSalt()
        val anonymizer = Anonymizer(settings.current().anonymizerSalt)
        val post = db.posts().get(chatId, postId) ?: throw LlmException("Post not found")
        val comments = db.comments().forPost(chatId, postId)
        val header = "POST (${date(post.date)}):\n${post.text.ifBlank { "[${post.mediaType ?: "no text"}]" }}\n\nCOMMENTS (${comments.size}):\n"
        val lines = comments.map { c ->
            val author = c.userId?.let { anonymizer.username(it) } ?: "channel"
            val reply = if (c.replyToMessageId != null) " ↩" else ""
            "[$author$reply] ${c.text.ifBlank { "[${c.mediaType ?: "empty"}]" }}"
        }
        val result = mapReduce(s, promptName, header, lines)
        return save(chatId, postId, s, promptName, result)
    }

    suspend fun analyzeChannel(chatId: Long, fromSeconds: Long, toSeconds: Long, promptName: String): AnalysisEntity {
        val s = requireConfigured()
        settings.ensureSalt()
        val anonymizer = Anonymizer(settings.current().anonymizerSalt)
        val channel = db.channels().get(chatId) ?: throw LlmException("Channel not found")
        val posts = db.posts().allForChannel(chatId).filter { it.date in fromSeconds until toSeconds }
        if (posts.isEmpty()) throw LlmException("No posts in the selected period")
        val header = "CHANNEL @${channel.username} — ${channel.title}\nPERIOD: ${date(fromSeconds)} … ${date(toSeconds)}\n\n"
        val lines = ArrayList<String>()
        for (post in posts) {
            lines += "=== POST ${date(post.date)} (views ${post.views}, comments ${post.replyCount}): ${post.text.take(500)}"
            db.comments().forPost(chatId, post.messageId).take(MAX_COMMENTS_PER_POST_IN_CHANNEL).forEach { c ->
                val author = c.userId?.let { anonymizer.username(it) } ?: "channel"
                lines += "  [$author] ${c.text.take(400)}"
            }
        }
        val result = mapReduce(s, promptName, header, lines)
        return save(chatId, 0, s, promptName, result)
    }

    private suspend fun save(chatId: Long, postId: Long, s: AppSettings, promptName: String, result: String): AnalysisEntity {
        val entity = AnalysisEntity(
            channelChatId = chatId,
            postId = postId,
            createdAt = System.currentTimeMillis(),
            language = s.llmLanguage,
            model = s.llmModel,
            promptName = promptName,
            result = result,
        )
        val id = db.analyses().insert(entity)
        return entity.copy(id = id)
    }

    private suspend fun requireConfigured(): AppSettings {
        val s = settings.current()
        if (!s.hasLlm) throw LlmException("LLM endpoint and model are not configured (Settings → LLM)")
        return s
    }

    private suspend fun mapReduce(s: AppSettings, promptName: String, header: String, lines: List<String>): String {
        val system = Prompts.system(promptName, s.llmLanguage)
        val chunks = chunk(lines, CHUNK_CHARS)
        if (chunks.size <= 1) return complete(s, system, header + chunks.firstOrNull().orEmpty())
        val partials = chunks.mapIndexed { i, body ->
            complete(s, system, "$header(part ${i + 1}/${chunks.size})\n$body")
        }
        val merged = partials.mapIndexed { i, p -> "### Part ${i + 1}\n$p" }.joinToString("\n\n")
        return complete(s, system + "\n\n" + Prompts.reduce(s.llmLanguage), merged)
    }

    private fun chunk(lines: List<String>, limit: Int): List<String> {
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (line in lines) {
            if (current.isNotEmpty() && current.length + line.length + 1 > limit) {
                chunks += current.toString()
                current.clear()
            }
            current.append(line.take(limit)).append('\n')
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    private suspend fun complete(s: AppSettings, system: String, user: String): String = withContext(Dispatchers.IO) {
        val body = ChatRequest(
            model = s.llmModel,
            messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
            temperature = 0.3,
        )
        val request = Request.Builder()
            .url(endpointUrl(s.llmEndpoint))
            .post(json.encodeToString(ChatRequest.serializer(), body).toRequestBody("application/json".toMediaType()))
            .apply { if (s.llmApiKey.isNotBlank()) header("Authorization", "Bearer ${s.llmApiKey}") }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmException("HTTP ${response.code}: ${text.take(300)}")
            val parsed = runCatching { json.decodeFromString(ChatResponse.serializer(), text) }
                .getOrElse { throw LlmException("Unexpected response: ${text.take(300)}") }
            parsed.choices.firstOrNull()?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw LlmException("Empty answer from model")
        }
    }

    private fun date(seconds: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochSecond(seconds))

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double,
    )

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: ChoiceMessage? = null)

    @Serializable
    private data class ChoiceMessage(val content: String? = null, @SerialName("role") val role: String? = null)

    companion object {
        private const val CHUNK_CHARS = 12_000
        private const val MAX_COMMENTS_PER_POST_IN_CHANNEL = 50

        fun endpointUrl(endpoint: String): String {
            val base = endpoint.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }
    }
}
