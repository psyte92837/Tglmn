package dev.tgscope.domain.export

import dev.tgscope.core.database.CommentEntity
import dev.tgscope.core.database.PostEntity
import dev.tgscope.core.database.ReactionEntity
import dev.tgscope.core.database.TgDatabase
import dev.tgscope.core.database.UserEntity
import dev.tgscope.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class ExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
}

data class ExportResult(val posts: Int, val comments: Int)

/**
 * Writes a channel dataset in the same JSON shape as the original Python tool
 * (list of posts → comments → user), or as a flat CSV of comments.
 */
class Exporter(
    private val db: TgDatabase,
    private val settings: SettingsRepository,
) {
    private val json = Json { prettyPrint = false }

    suspend fun export(chatId: Long, format: ExportFormat, anonymize: Boolean, out: OutputStream): ExportResult =
        withContext(Dispatchers.IO) {
            settings.ensureSalt()
            val anonymizer = if (anonymize) Anonymizer(settings.current().anonymizerSalt) else null
            val posts = db.posts().allForChannel(chatId)
            val postReactions = db.reactions().forChat(ReactionEntity.OWNER_POST, chatId)
                .groupBy { it.messageId }
            val discussionId = db.channels().get(chatId)?.discussionChatId ?: 0L
            val commentReactions = if (discussionId != 0L) {
                db.reactions().forChat(ReactionEntity.OWNER_COMMENT, discussionId).groupBy { it.messageId }
            } else {
                emptyMap()
            }
            val writer = out.bufferedWriter(Charsets.UTF_8)
            val result = when (format) {
                ExportFormat.JSON -> writeJson(writer, posts, postReactions, commentReactions, anonymizer)
                ExportFormat.CSV -> writeCsv(writer, posts, commentReactions, anonymizer)
            }
            writer.flush()
            result
        }

    private suspend fun writeJson(
        writer: Writer,
        posts: List<PostEntity>,
        postReactions: Map<Long, List<ReactionEntity>>,
        commentReactions: Map<Long, List<ReactionEntity>>,
        anonymizer: Anonymizer?,
    ): ExportResult {
        var commentCount = 0
        writer.write("[\n")
        posts.sortedByDescending { it.messageId }.forEachIndexed { index, post ->
            val comments = db.comments().forPost(post.chatId, post.messageId)
            val users = usersFor(comments)
            commentCount += comments.size
            val obj = buildJsonObject {
                put("post_id", post.messageId.serverId())
                put("post_date", formatDate(post.date))
                put("post_text", post.text)
                put("post_views", post.views)
                put("post_forwards", post.forwards)
                put("post_link", post.link)
                put("post_media", post.mediaPath ?: post.mediaType)
                put("post_reactions", reactionsJson(postReactions[post.messageId]))
                put(
                    "comments",
                    buildJsonArray {
                        comments.forEach { c -> add(commentJson(c, c.userId?.let { users[it] }, commentReactions[c.messageId], anonymizer)) }
                    },
                )
            }
            if (index > 0) writer.write(",\n")
            writer.write(json.encodeToString(JsonObject.serializer(), obj))
        }
        writer.write("\n]\n")
        return ExportResult(posts.size, commentCount)
    }

    private fun commentJson(
        c: CommentEntity,
        user: UserEntity?,
        reactions: List<ReactionEntity>?,
        anonymizer: Anonymizer?,
    ): JsonObject = buildJsonObject {
        put("comment_id", c.messageId.serverId())
        put("comment_date", formatDate(c.date))
        put("comment_text", c.text)
        put("comment_link", c.link)
        put("comment_media", c.mediaPath ?: c.mediaType)
        put("reply_to_msg_id", c.replyToMessageId?.serverId())
        put("comment_reactions", reactionsJson(reactions))
        val userId = c.userId
        if (userId == null) {
            put("user", JsonNull)
        } else {
            put(
                "user",
                buildJsonObject {
                    if (anonymizer != null) {
                        put("user_id", anonymizer.userId(userId))
                        put("username", anonymizer.username(userId))
                        put("first_name", JsonNull)
                        put("last_name", JsonNull)
                    } else {
                        put("user_id", userId)
                        put("username", user?.username)
                        put("first_name", user?.firstName)
                        put("last_name", user?.lastName?.ifEmpty { null })
                    }
                    put("bot", user?.isBot ?: false)
                    put("premium", user?.isPremium ?: false)
                },
            )
        }
    }

    private fun reactionsJson(reactions: List<ReactionEntity>?): JsonArray = buildJsonArray {
        reactions.orEmpty().sortedByDescending { it.count }.forEach {
            add(buildJsonObject { put("emoji", it.emoji); put("count", it.count) })
        }
    }

    private suspend fun writeCsv(
        writer: Writer,
        posts: List<PostEntity>,
        commentReactions: Map<Long, List<ReactionEntity>>,
        anonymizer: Anonymizer?,
    ): ExportResult {
        var commentCount = 0
        writer.write("﻿") // BOM so Excel detects UTF-8
        writer.write(
            listOf(
                "post_id", "post_date", "post_views", "post_link", "comment_id", "comment_date",
                "reply_to_msg_id", "user_id", "username", "first_name", "last_name", "comment_text",
                "comment_reactions", "comment_link",
            ).joinToString(",") + "\n",
        )
        for (post in posts.sortedByDescending { it.messageId }) {
            val comments = db.comments().forPost(post.chatId, post.messageId)
            val users = usersFor(comments)
            commentCount += comments.size
            for (c in comments) {
                val user = c.userId?.let { users[it] }
                val uid = c.userId
                val row = listOf(
                    post.messageId.serverId().toString(),
                    formatDate(post.date),
                    post.views.toString(),
                    post.link,
                    c.messageId.serverId().toString(),
                    formatDate(c.date),
                    c.replyToMessageId?.serverId()?.toString() ?: "",
                    if (uid == null) "" else (anonymizer?.userId(uid) ?: uid).toString(),
                    if (uid == null) "" else (anonymizer?.username(uid) ?: user?.username.orEmpty()),
                    if (anonymizer != null) "" else user?.firstName.orEmpty(),
                    if (anonymizer != null) "" else user?.lastName.orEmpty(),
                    c.text,
                    commentReactions[c.messageId].orEmpty().joinToString(";") { "${it.emoji}:${it.count}" },
                    c.link,
                )
                writer.write(row.joinToString(",") { csvEscape(it) } + "\n")
            }
        }
        return ExportResult(posts.size, commentCount)
    }

    private suspend fun usersFor(comments: List<CommentEntity>): Map<Long, UserEntity> {
        val ids = comments.mapNotNull { it.userId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(500).flatMap { db.users().getMany(it) }.associateBy { it.userId }
    }

    companion object {
        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssxxx").withZone(ZoneOffset.UTC)

        fun formatDate(unixSeconds: Long): String = dateFormat.format(Instant.ofEpochSecond(unixSeconds))

        /** TDLib message ids are server ids shifted left by 20 bits. */
        fun Long.serverId(): Long = this shr 20

        fun csvEscape(value: String): String {
            val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
            return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
        }

        fun fileName(username: String, format: ExportFormat): String {
            val ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
            return "${username}_$ts.${format.extension}"
        }
    }
}
