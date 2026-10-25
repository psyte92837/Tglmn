package dev.tgscope.domain.sync

import dev.tgscope.core.database.ReactionEntity
import dev.tgscope.core.database.TgDatabase
import dev.tgscope.core.tdlib.TelegramClient
import dev.tgscope.core.tdlib.TgUpdate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Watch mode: keeps chats opened so TDLib streams their updates, applies cheap counter updates
 * directly and batches structural changes (new posts/comments, edits) into debounced syncs.
 */
class WatchCoordinator(
    private val tg: TelegramClient,
    private val db: TgDatabase,
    private val engine: SyncEngine,
) {
    /** Runs until cancelled. [debounceMs] groups bursts of updates into one sync per channel. */
    suspend fun run(debounceMs: Long = 30_000, activePosts: Int = 20): Unit = coroutineScope {
        val channels = db.channels().getAll()
        val byChannel = channels.associateBy { it.chatId }
        val byDiscussion = channels.filter { it.discussionChatId != 0L }.associateBy { it.discussionChatId }
        val opened = channels.flatMap { listOf(it.chatId, it.discussionChatId) }.filter { it != 0L }
        opened.forEach { tg.openChat(it) }

        val dirty = ConcurrentHashMap.newKeySet<Long>()
        launch {
            while (isActive) {
                delay(debounceMs)
                val batch = dirty.toList()
                dirty.removeAll(batch.toSet())
                for (chatId in batch) {
                    engine.refreshRecent(chatId, activePosts)
                }
            }
        }

        try {
            tg.updates.collect { update ->
                val channel = byChannel[update.chatId]
                val viaDiscussion = byDiscussion[update.chatId]
                if (channel == null && viaDiscussion == null) return@collect
                when (update) {
                    is TgUpdate.InteractionInfo -> if (channel != null) {
                        val changed = db.posts().updateCounters(
                            chatId = update.chatId,
                            messageId = update.messageId,
                            views = update.views,
                            forwards = update.forwards,
                            replyCount = update.replyCount,
                            at = System.currentTimeMillis(),
                        )
                        if (changed > 0) {
                            db.reactions().replace(
                                ReactionEntity.OWNER_POST, update.chatId, update.messageId,
                                update.reactions.map {
                                    ReactionEntity(ReactionEntity.OWNER_POST, update.chatId, update.messageId, it.emoji, it.count)
                                },
                            )
                        }
                        // New comments raise replyCount: fetch them on the next tick.
                        dirty += update.chatId
                    }
                    is TgUpdate.Deleted -> {
                        if (channel != null) db.posts().markDeleted(update.chatId, update.messageIds)
                        if (viaDiscussion != null) db.comments().markDeleted(update.chatId, update.messageIds)
                    }
                    is TgUpdate.NewMessage, is TgUpdate.ContentChanged -> {
                        dirty += (channel ?: viaDiscussion)!!.chatId
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { opened.forEach { tg.closeChat(it) } }
        }
    }
}
