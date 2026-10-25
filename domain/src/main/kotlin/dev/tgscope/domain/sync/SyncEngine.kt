package dev.tgscope.domain.sync

import androidx.room.withTransaction
import dev.tgscope.core.database.ChannelEntity
import dev.tgscope.core.database.CommentEntity
import dev.tgscope.core.database.PostEntity
import dev.tgscope.core.database.ReactionEntity
import dev.tgscope.core.database.SyncRunEntity
import dev.tgscope.core.database.TgDatabase
import dev.tgscope.core.database.UserEntity
import dev.tgscope.core.settings.AppSettings
import dev.tgscope.core.settings.SettingsRepository
import dev.tgscope.core.tdlib.FloodWaitException
import dev.tgscope.core.tdlib.TelegramClient
import dev.tgscope.core.tdlib.TgException
import dev.tgscope.core.tdlib.TgLinks
import dev.tgscope.core.tdlib.TgMessage
import dev.tgscope.core.tdlib.TgReaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

enum class SyncMode(val id: String) { MANUAL("manual"), SCHEDULER("scheduler"), WATCH("watch") }

data class SyncStats(
    val newPosts: Int = 0,
    val updatedPosts: Int = 0,
    val newComments: Int = 0,
)

sealed interface SyncOutcome {
    data class Success(val stats: SyncStats) : SyncOutcome

    /** Telegram rate-limited us; everything collected so far is saved. */
    data class Partial(val stats: SyncStats, val retryAfterSeconds: Int) : SyncOutcome
    data class Failed(val error: String) : SyncOutcome

    /** Another run for the same channel is in progress. */
    data object Skipped : SyncOutcome
}

data class SyncProgress(val stage: String, val done: Int, val total: Int)

/**
 * Incremental exporter. Mirrors the original tool:
 * 1. scan posts newer than `lastPostId` (up to POST_LIMIT);
 * 2. look back over the newest N stored posts to refresh counters, reactions and comments;
 * 3. store each post atomically (post + reactions + comments + authors in one transaction),
 *    oldest first, moving `lastPostId` only past fully stored posts;
 * 4. on FloodWait keep what is stored and report how long to wait.
 */
class SyncEngine(
    private val tg: TelegramClient,
    private val db: TgDatabase,
    private val settings: SettingsRepository,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    @Volatile
    private var floodUntil: Long = 0

    private val _progress = MutableStateFlow<Map<Long, SyncProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, SyncProgress>> = _progress.asStateFlow()

    suspend fun addChannel(input: String): ChannelEntity {
        val resolved = tg.resolveChannel(input)
        val existing = db.channels().get(resolved.chatId)
        val entity = existing?.copy(
            username = resolved.username,
            title = resolved.title,
            discussionChatId = resolved.discussionChatId,
        ) ?: ChannelEntity(
            chatId = resolved.chatId,
            username = resolved.username,
            title = resolved.title,
            discussionChatId = resolved.discussionChatId,
        )
        db.channels().upsert(entity)
        return entity
    }

    suspend fun removeChannel(chatId: Long) {
        val channel = db.channels().get(chatId) ?: return
        db.withTransaction {
            db.comments().deleteForChannel(chatId)
            db.posts().deleteForChannel(chatId)
            db.reactions().deleteForChat(chatId)
            if (channel.discussionChatId != 0L) db.reactions().deleteForChat(channel.discussionChatId)
            db.channels().delete(chatId)
        }
    }

    suspend fun sync(chatId: Long, mode: SyncMode): SyncOutcome {
        val lock = locks.getOrPut(chatId) { Mutex() }
        if (!lock.tryLock()) {
            val now = System.currentTimeMillis()
            db.syncRuns().insert(
                SyncRunEntity(channelChatId = chatId, mode = mode.id, startedAt = now, finishedAt = now, status = "SKIPPED"),
            )
            return SyncOutcome.Skipped
        }
        try {
            return runSync(chatId, mode)
        } finally {
            lock.unlock()
            _progress.update { it - chatId }
        }
    }

    private suspend fun runSync(chatId: Long, mode: SyncMode): SyncOutcome {
        val channel = db.channels().get(chatId) ?: return SyncOutcome.Failed("Unknown channel $chatId")
        val s = settings.current()
        val runId = db.syncRuns().insert(
            SyncRunEntity(channelChatId = chatId, mode = mode.id, startedAt = System.currentTimeMillis(), status = "RUNNING"),
        )
        val counter = Counter()
        var status = "SUCCESS"
        var error: String? = null
        var outcome: SyncOutcome
        try {
            val waitMs = floodUntil - System.currentTimeMillis()
            if (waitMs > 0) throw FloodWaitException(((waitMs + 999) / 1000).toInt(), "Waiting for previous FloodWait")
            scanAndStore(channel, s, counter)
            outcome = SyncOutcome.Success(counter.stats())
        } catch (e: FloodWaitException) {
            floodUntil = System.currentTimeMillis() + e.retryAfterSeconds * 1000L
            status = "PARTIAL"
            error = "FloodWait ${e.retryAfterSeconds}s"
            outcome = SyncOutcome.Partial(counter.stats(), e.retryAfterSeconds)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finish(runId, chatId, "CANCELLED", counter, null) }
            throw e
        } catch (e: Exception) {
            status = if (counter.any()) "PARTIAL" else "FAILED"
            error = e.message ?: e::class.simpleName
            outcome = SyncOutcome.Failed(error ?: "error")
        }
        finish(runId, chatId, status, counter, error)
        return outcome
    }

    private suspend fun finish(runId: Long, chatId: Long, status: String, counter: Counter, error: String?) {
        val now = System.currentTimeMillis()
        db.channels().setLastSyncAt(chatId, now)
        db.syncRuns().finish(
            id = runId,
            finishedAt = now,
            status = status,
            newPosts = counter.newPosts,
            updatedPosts = counter.updatedPosts,
            newComments = counter.newComments,
            error = error,
        )
    }

    private suspend fun scanAndStore(channel: ChannelEntity, s: AppSettings, counter: Counter) {
        val chatId = channel.chatId

        // 1. New posts, newest first, until we reach what is already stored.
        val fresh = ArrayList<TgMessage>()
        var from = 0L
        scan@ while (fresh.size < s.postLimit) {
            report(chatId, "scan", fresh.size, s.postLimit)
            val batch = tg.getHistory(chatId, from, minOf(100, s.postLimit - fresh.size))
                .filter { from == 0L || it.id < from } // fromMessageId itself is included by TDLib
            if (batch.isEmpty()) break
            for (m in batch) {
                if (m.id <= channel.lastPostId) break@scan
                fresh += m
                if (fresh.size >= s.postLimit) break@scan
            }
            from = batch.last().id
        }

        // Store oldest first so lastPostId only ever covers fully stored posts.
        fresh.sortBy { it.id }
        var processed = 0
        var lastPostId = channel.lastPostId
        for (m in fresh) {
            report(chatId, "posts", processed, fresh.size)
            storePost(channel, m, s, counter, isNew = true)
            lastPostId = max(lastPostId, m.id)
            db.channels().setLastPostId(chatId, lastPostId)
            processed++
            pauseIfNeeded(processed, s)
        }

        // 2. Look back at already stored posts: counters, reactions, new comments, deletions.
        if (s.lookbackPosts > 0) {
            val freshIds = fresh.mapTo(HashSet()) { it.id }
            val known = db.posts().latestIds(chatId, s.lookbackPosts + fresh.size)
                .filter { it !in freshIds }
                .take(s.lookbackPosts)
            if (known.isNotEmpty()) {
                val refreshed = tg.getMessages(chatId, known)
                val gone = ArrayList<Long>()
                known.zip(refreshed).forEachIndexed { index, (id, message) ->
                    report(chatId, "lookback", index, known.size)
                    if (message == null) {
                        gone += id
                    } else {
                        storePost(channel, message, s, counter, isNew = false)
                        processed++
                        pauseIfNeeded(processed, s)
                    }
                }
                if (gone.isNotEmpty()) db.posts().markDeleted(chatId, gone)
            }
        }
    }

    /** Refreshes the newest [count] stored posts only (used by Watch mode for "active" posts). */
    suspend fun refreshRecent(chatId: Long, count: Int): SyncOutcome {
        val channel = db.channels().get(chatId) ?: return SyncOutcome.Failed("Unknown channel $chatId")
        if (floodUntil > System.currentTimeMillis()) {
            return SyncOutcome.Partial(SyncStats(), ((floodUntil - System.currentTimeMillis()) / 1000).toInt() + 1)
        }
        val lock = locks.getOrPut(chatId) { Mutex() }
        if (!lock.tryLock()) return SyncOutcome.Skipped
        val s = settings.current().copy(postLimit = count, lookbackPosts = count)
        val counter = Counter()
        return try {
            scanAndStore(channel, s, counter)
            db.channels().setLastSyncAt(chatId, System.currentTimeMillis())
            SyncOutcome.Success(counter.stats())
        } catch (e: FloodWaitException) {
            floodUntil = System.currentTimeMillis() + e.retryAfterSeconds * 1000L
            SyncOutcome.Partial(counter.stats(), e.retryAfterSeconds)
        } catch (e: TgException) {
            SyncOutcome.Failed(e.message ?: "error")
        } finally {
            lock.unlock()
            _progress.update { it - chatId }
        }
    }

    private suspend fun storePost(
        channel: ChannelEntity,
        m: TgMessage,
        s: AppSettings,
        counter: Counter,
        isNew: Boolean,
    ) {
        val chatId = channel.chatId
        val existing = db.posts().get(chatId, m.id)
        val since = existing?.lastCommentId ?: 0L

        val needComments = channel.discussionChatId != 0L && m.replyCount > 0 &&
            (existing == null || existing.replyCount != m.replyCount || since == 0L)
        val comments = if (needComments) fetchNewComments(chatId, m.id, since) else emptyList()
        val users = resolveUsers(comments.mapNotNull { it.senderUserId }.distinct())

        var postMediaPath = existing?.mediaPath
        if (s.downloadMedia && postMediaPath == null && m.mediaFileId != null) {
            postMediaPath = download(m.mediaFileId!!)
        }
        val commentMedia = HashMap<Long, String>()
        if (s.downloadMedia) {
            for (c in comments) {
                val fileId = c.mediaFileId ?: continue
                download(fileId)?.let { commentMedia[c.id] = it }
            }
        }

        val now = System.currentTimeMillis()
        val newLastCommentId = max(since, comments.maxOfOrNull { it.id } ?: 0L)
        db.withTransaction {
            db.posts().upsert(
                PostEntity(
                    chatId = chatId,
                    messageId = m.id,
                    date = m.date,
                    editDate = m.editDate,
                    text = m.text,
                    views = m.views,
                    forwards = m.forwards,
                    replyCount = m.replyCount,
                    lastCommentId = newLastCommentId,
                    mediaType = m.mediaType,
                    mediaFileId = m.mediaFileId,
                    mediaPath = postMediaPath,
                    link = TgLinks.post(channel.username, m.serverId),
                    updatedAt = now,
                ),
            )
            db.reactions().replace(ReactionEntity.OWNER_POST, chatId, m.id, m.reactions.toEntities(ReactionEntity.OWNER_POST, chatId, m.id))
            if (users.isNotEmpty()) db.users().upsert(users)
            if (comments.isNotEmpty()) {
                db.comments().upsert(
                    comments.map { c ->
                        CommentEntity(
                            chatId = c.chatId,
                            messageId = c.id,
                            postChatId = chatId,
                            postId = m.id,
                            date = c.date,
                            editDate = c.editDate,
                            text = c.text,
                            replyToMessageId = c.replyToMessageId,
                            userId = c.senderUserId,
                            senderChatId = c.senderChatId,
                            mediaType = c.mediaType,
                            mediaFileId = c.mediaFileId,
                            mediaPath = commentMedia[c.id],
                            link = TgLinks.comment(channel.username, m.serverId, c.serverId),
                            updatedAt = now,
                        )
                    },
                )
                for (c in comments) {
                    if (c.reactions.isNotEmpty()) {
                        db.reactions().replace(
                            ReactionEntity.OWNER_COMMENT, c.chatId, c.id,
                            c.reactions.toEntities(ReactionEntity.OWNER_COMMENT, c.chatId, c.id),
                        )
                    }
                }
            }
        }
        if (isNew) counter.newPosts++ else counter.updatedPosts++
        counter.newComments += comments.size
    }

    private suspend fun fetchNewComments(channelChatId: Long, postId: Long, sinceId: Long): List<TgMessage> {
        val out = ArrayList<TgMessage>()
        var from = 0L
        page@ while (out.size < MAX_COMMENTS_PER_POST) {
            val batch = try {
                tg.getComments(channelChatId, postId, from, 100)
            } catch (e: FloodWaitException) {
                throw e
            } catch (e: TgException) {
                // 400: the post has no discussion thread (e.g. service message or comments off).
                if (e.code == 400) break else throw e
            }
            val page = batch.filter { from == 0L || it.id < from }
            if (page.isEmpty()) break
            for (c in page) {
                if (c.id <= sinceId) break@page
                out += c
            }
            from = page.last().id
        }
        return out
    }

    private suspend fun resolveUsers(ids: List<Long>): List<UserEntity> {
        if (ids.isEmpty()) return emptyList()
        val known = db.users().existing(ids).toHashSet()
        val now = System.currentTimeMillis()
        return ids.filter { it !in known }.mapNotNull { id ->
            try {
                val u = tg.getUser(id)
                UserEntity(
                    userId = u.id,
                    username = u.username,
                    firstName = u.firstName,
                    lastName = u.lastName,
                    isBot = u.isBot,
                    isPremium = u.isPremium,
                    updatedAt = now,
                )
            } catch (e: FloodWaitException) {
                throw e
            } catch (e: TgException) {
                null
            }
        }
    }

    private suspend fun download(fileId: Int): String? = try {
        tg.downloadFile(fileId)
    } catch (e: FloodWaitException) {
        throw e
    } catch (e: TgException) {
        null
    }

    private suspend fun pauseIfNeeded(processed: Int, s: AppSettings) {
        if (s.pauseSeconds > 0 && processed % s.pauseAfterPosts == 0) {
            delay(s.pauseSeconds * 1000L)
        }
    }

    private fun report(chatId: Long, stage: String, done: Int, total: Int) {
        _progress.update { it + (chatId to SyncProgress(stage, done, total)) }
    }

    private fun List<TgReaction>.toEntities(ownerType: Int, chatId: Long, messageId: Long) =
        map { ReactionEntity(ownerType = ownerType, chatId = chatId, messageId = messageId, emoji = it.emoji, count = it.count) }

    private class Counter {
        var newPosts = 0
        var updatedPosts = 0
        var newComments = 0
        fun any() = newPosts + updatedPosts + newComments > 0
        fun stats() = SyncStats(newPosts, updatedPosts, newComments)
    }

    private companion object {
        const val MAX_COMMENTS_PER_POST = 10_000
    }
}
