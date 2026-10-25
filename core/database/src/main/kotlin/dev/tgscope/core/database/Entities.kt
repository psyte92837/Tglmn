package dev.tgscope.core.database

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "channel")
data class ChannelEntity(
    @PrimaryKey val chatId: Long,
    val username: String,
    val title: String,
    /** Linked discussion group (where comments live), 0 if comments are off. */
    val discussionChatId: Long,
    /** Newest post id that has been fully stored: the incremental export "compass". */
    val lastPostId: Long = 0,
    val lastSyncAt: Long = 0,
    /** Whether the periodic scheduler should refresh this channel. */
    val scheduled: Boolean = false,
    val intervalMinutes: Int = 60,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "post",
    primaryKeys = ["chatId", "messageId"],
    indices = [Index("date")],
)
data class PostEntity(
    val chatId: Long,
    val messageId: Long,
    /** Unix seconds. */
    val date: Long,
    val editDate: Long,
    val text: String,
    val views: Int,
    val forwards: Int,
    val replyCount: Int,
    /** Newest stored comment id, used to fetch only new comments. */
    val lastCommentId: Long = 0,
    val mediaType: String?,
    val mediaFileId: Int?,
    val mediaPath: String? = null,
    val link: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "comment",
    primaryKeys = ["chatId", "messageId"],
    indices = [Index("postChatId", "postId"), Index("userId"), Index("date")],
)
data class CommentEntity(
    /** Discussion group chat id. */
    val chatId: Long,
    val messageId: Long,
    /** Channel chat id and post id the comment belongs to. */
    val postChatId: Long,
    val postId: Long,
    val date: Long,
    val editDate: Long,
    val text: String,
    /** Id of the comment this one replies to (inside the same discussion group). */
    val replyToMessageId: Long?,
    val userId: Long?,
    val senderChatId: Long?,
    val mediaType: String?,
    val mediaFileId: Int?,
    val mediaPath: String? = null,
    val link: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** Full-text index over comment texts (kept in sync by Room triggers). */
@Fts4(contentEntity = CommentEntity::class)
@Entity(tableName = "comment_fts")
data class CommentFts(val text: String)

@Entity(tableName = "tg_user")
data class UserEntity(
    @PrimaryKey val userId: Long,
    val username: String?,
    val firstName: String,
    val lastName: String,
    val isBot: Boolean,
    val isPremium: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "reaction",
    primaryKeys = ["ownerType", "chatId", "messageId", "emoji"],
    indices = [Index("emoji")],
)
data class ReactionEntity(
    /** [OWNER_POST] or [OWNER_COMMENT]. */
    val ownerType: Int,
    val chatId: Long,
    val messageId: Long,
    val emoji: String,
    val count: Int,
) {
    companion object {
        const val OWNER_POST = 0
        const val OWNER_COMMENT = 1
    }
}

@Entity(tableName = "sync_run", indices = [Index("channelChatId")])
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelChatId: Long,
    /** manual | scheduler | watch */
    val mode: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    /** QUEUED, RUNNING, SUCCESS, PARTIAL, FAILED, SKIPPED, CANCELLED */
    val status: String,
    val newPosts: Int = 0,
    val updatedPosts: Int = 0,
    val newComments: Int = 0,
    val error: String? = null,
)

@Entity(tableName = "analysis", indices = [Index("channelChatId")])
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelChatId: Long,
    /** 0 for a whole-channel analysis. */
    val postId: Long,
    val createdAt: Long,
    val language: String,
    val model: String,
    val promptName: String,
    val result: String,
)
