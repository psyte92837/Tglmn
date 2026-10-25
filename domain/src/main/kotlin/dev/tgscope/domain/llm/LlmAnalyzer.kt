package dev.tgscope.domain.llm

import dev.tgscope.core.database.AnalysisEntity
import dev.tgscope.core.database.TgDatabase
import dev.tgscope.core.settings.AppSettings
import dev.tgscope.core.settings.SettingsRepository
import dev.tgscope.domain.export.Anonymizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Runs catalog analyses (or a custom instruction) over stored discussions with the configured
 * provider. Long inputs are split into chunks, analyzed separately and merged (map-reduce).
 * Author names are replaced with stable aliases before anything leaves the device.
 */
class LlmAnalyzer(
    private val db: TgDatabase,
    private val settings: SettingsRepository,
    private val client: LlmClient = LlmClient(),
) {
    fun config(s: AppSettings): LlmConfig = LlmConfig(
        style = LlmProviders.byId(s.llmProvider).style,
        endpoint = s.llmEndpoint,
        apiKey = s.llmApiKey,
        model = s.llmModel,
        maxTokens = s.llmMaxTokens,
        temperature = s.llmTemperature.toDouble(),
    )

    /** Loads the provider's live model list using the given (possibly unsaved) settings. */
    suspend fun listModels(s: AppSettings): List<String> = client.listModels(config(s))

    /** Sends a tiny request to verify endpoint, key and model. */
    suspend fun test(s: AppSettings): String =
        client.complete(config(s).copy(maxTokens = 32), "Reply with one word.", "Say OK")

    suspend fun analyzePost(chatId: Long, postId: Long, kindId: String, customTask: String? = null): AnalysisEntity {
        val s = requireConfigured()
        val anonymizer = anonymizer()
        val post = db.posts().get(chatId, postId) ?: throw LlmException("Пост не найден")
        val comments = db.comments().forPost(chatId, postId)
        val header = "POST (${date(post.date)}, views ${post.views}, comments ${post.replyCount}):\n" +
            post.text.ifBlank { "[${post.mediaType ?: "no text"}]" } + "\n\nCOMMENTS (${comments.size}):\n"
        val lines = comments.map { c ->
            val author = c.userId?.let { anonymizer.username(it) } ?: "channel"
            val reply = if (c.replyToMessageId != null) " ↩" else ""
            "[$author$reply] ${c.text.ifBlank { "[${c.mediaType ?: "empty"}]" }}"
        }
        val result = mapReduce(s, kindId, customTask, header, lines)
        return save(chatId, postId, s, kindId, result)
    }

    suspend fun analyzeChannel(
        chatId: Long,
        fromSeconds: Long,
        toSeconds: Long,
        kindId: String,
        customTask: String? = null,
    ): AnalysisEntity {
        val s = requireConfigured()
        val anonymizer = anonymizer()
        val channel = db.channels().get(chatId) ?: throw LlmException("Канал не найден")
        val posts = db.posts().allForChannel(chatId).filter { it.date in fromSeconds until toSeconds }
        if (posts.isEmpty()) throw LlmException("За выбранный период нет постов")
        val header = "CHANNEL @${channel.username} — ${channel.title}\nPERIOD: ${date(fromSeconds)} … ${date(toSeconds)}\n\n"
        val lines = ArrayList<String>()
        for (post in posts) {
            lines += "=== POST ${date(post.date)} (views ${post.views}, comments ${post.replyCount}, forwards ${post.forwards}): ${post.text.take(600)}"
            db.comments().forPost(chatId, post.messageId).take(MAX_COMMENTS_PER_POST_IN_CHANNEL).forEach { c ->
                val author = c.userId?.let { anonymizer.username(it) } ?: "channel"
                lines += "  [$author] ${c.text.take(400)}"
            }
        }
        val result = mapReduce(s, kindId, customTask, header, lines)
        return save(chatId, 0, s, kindId, result)
    }

    private suspend fun anonymizer(): Anonymizer {
        settings.ensureSalt()
        return Anonymizer(settings.current().anonymizerSalt)
    }

    private suspend fun save(chatId: Long, postId: Long, s: AppSettings, kindId: String, result: String): AnalysisEntity {
        val entity = AnalysisEntity(
            channelChatId = chatId,
            postId = postId,
            createdAt = System.currentTimeMillis(),
            language = s.llmLanguage,
            model = "${LlmProviders.byId(s.llmProvider).name} · ${s.llmModel}",
            promptName = kindId,
            result = result,
        )
        val id = db.analyses().insert(entity)
        return entity.copy(id = id)
    }

    private suspend fun requireConfigured(): AppSettings {
        val s = settings.current()
        if (!s.hasLlm) throw LlmException("Не настроен ИИ-провайдер и модель (Настройки → ИИ)")
        if (LlmProviders.byId(s.llmProvider).needsKey && s.llmApiKey.isBlank()) {
            throw LlmException("Не указан API-ключ для ${LlmProviders.byId(s.llmProvider).name}")
        }
        return s
    }

    private suspend fun mapReduce(
        s: AppSettings,
        kindId: String,
        customTask: String?,
        header: String,
        lines: List<String>,
    ): String {
        val cfg = config(s)
        val system = Prompts.system(kindId, s.llmLanguage, customTask)
        val chunks = chunk(lines, CHUNK_CHARS)
        if (chunks.size <= 1) return client.complete(cfg, system, header + chunks.firstOrNull().orEmpty())
        val partials = chunks.mapIndexed { i, body ->
            client.complete(cfg, system, "$header(part ${i + 1}/${chunks.size})\n$body")
        }
        val merged = partials.mapIndexed { i, p -> "### Part ${i + 1}\n$p" }.joinToString("\n\n")
        return client.complete(cfg, system + "\n\n" + Prompts.reduce(s.llmLanguage), merged)
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

    private fun date(seconds: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochSecond(seconds))

    companion object {
        private const val CHUNK_CHARS = 12_000
        private const val MAX_COMMENTS_PER_POST_IN_CHANNEL = 50

        fun endpointUrl(endpoint: String): String = LlmClient.chatUrl(endpoint)
    }
}
