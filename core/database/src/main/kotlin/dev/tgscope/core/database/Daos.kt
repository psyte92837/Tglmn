package dev.tgscope.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class PostRow(
    @Embedded val post: PostEntity,
    val reactionsTotal: Int,
)

data class CommentRow(
    @Embedded val comment: CommentEntity,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

data class DayCount(val day: String, val count: Int)

data class UserCount(
    val userId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val count: Int,
)

data class EmojiCount(val emoji: String, val total: Int)

/** Lightweight comment projection for in-memory analytics. */
data class CommentLite(
    val messageId: Long,
    val postId: Long,
    val date: Long,
    val userId: Long?,
    val replyToMessageId: Long?,
    val text: String,
)

data class IdTotal(val messageId: Long, val total: Int)

data class UserFirst(val userId: Long, val first: Long)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channel ORDER BY addedAt")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channel ORDER BY addedAt")
    suspend fun getAll(): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE chatId = :chatId")
    suspend fun get(chatId: Long): ChannelEntity?

    @Query("SELECT * FROM channel WHERE chatId = :chatId")
    fun observe(chatId: Long): Flow<ChannelEntity?>

    @Query("SELECT * FROM channel WHERE discussionChatId = :discussionChatId LIMIT 1")
    suspend fun byDiscussion(discussionChatId: Long): ChannelEntity?

    @Upsert
    suspend fun upsert(channel: ChannelEntity)

    @Query("UPDATE channel SET lastPostId = :lastPostId WHERE chatId = :chatId")
    suspend fun setLastPostId(chatId: Long, lastPostId: Long)

    @Query("UPDATE channel SET lastSyncAt = :at WHERE chatId = :chatId")
    suspend fun setLastSyncAt(chatId: Long, at: Long)

    @Query("UPDATE channel SET scheduled = :scheduled, intervalMinutes = :intervalMinutes WHERE chatId = :chatId")
    suspend fun setSchedule(chatId: Long, scheduled: Boolean, intervalMinutes: Int)

    @Query("DELETE FROM channel WHERE chatId = :chatId")
    suspend fun delete(chatId: Long)
}

@Dao
interface PostDao {
    @Upsert
    suspend fun upsert(post: PostEntity)

    @Query("SELECT * FROM post WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun get(chatId: Long, messageId: Long): PostEntity?

    @Query("SELECT * FROM post WHERE chatId = :chatId AND messageId = :messageId")
    fun observe(chatId: Long, messageId: Long): Flow<PostEntity?>

    @Query("SELECT messageId FROM post WHERE chatId = :chatId AND deleted = 0 ORDER BY messageId DESC LIMIT :limit")
    suspend fun latestIds(chatId: Long, limit: Int): List<Long>

    @Query("SELECT * FROM post WHERE chatId = :chatId AND deleted = 0 ORDER BY messageId")
    suspend fun allForChannel(chatId: Long): List<PostEntity>

    @Query(
        """
        SELECT p.*, COALESCE((SELECT SUM(r.count) FROM reaction r
            WHERE r.ownerType = 0 AND r.chatId = p.chatId AND r.messageId = p.messageId), 0) AS reactionsTotal
        FROM post p
        WHERE p.chatId = :chatId AND p.deleted = 0
          AND (:query = '' OR p.text LIKE '%' || :query || '%')
          AND (:withMedia = 0 OR p.mediaType IS NOT NULL)
          AND (:withComments = 0 OR p.replyCount > 0)
          AND p.date >= :from AND p.date < :to
        ORDER BY p.messageId DESC
        LIMIT :limit
        """,
    )
    fun observeFeed(
        chatId: Long,
        query: String,
        withMedia: Boolean,
        withComments: Boolean,
        from: Long,
        to: Long,
        limit: Int,
    ): Flow<List<PostRow>>

    @Query(
        """
        UPDATE post SET views = :views, forwards = :forwards, replyCount = :replyCount, updatedAt = :at
        WHERE chatId = :chatId AND messageId = :messageId
        """,
    )
    suspend fun updateCounters(chatId: Long, messageId: Long, views: Int, forwards: Int, replyCount: Int, at: Long): Int

    @Query("UPDATE post SET lastCommentId = :lastCommentId WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun setLastCommentId(chatId: Long, messageId: Long, lastCommentId: Long)

    @Query("UPDATE post SET mediaPath = :path WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun setMediaPath(chatId: Long, messageId: Long, path: String)

    @Query("UPDATE post SET deleted = 1 WHERE chatId = :chatId AND messageId IN (:ids)")
    suspend fun markDeleted(chatId: Long, ids: List<Long>)

    @Query("SELECT COUNT(*) FROM post WHERE chatId = :chatId AND deleted = 0")
    fun observeCount(chatId: Long): Flow<Int>

    @Query("DELETE FROM post WHERE chatId = :chatId")
    suspend fun deleteForChannel(chatId: Long)
}

@Dao
interface CommentDao {
    @Upsert
    suspend fun upsert(comments: List<CommentEntity>)

    @Query("SELECT MAX(messageId) FROM comment WHERE postChatId = :postChatId AND postId = :postId")
    suspend fun maxId(postChatId: Long, postId: Long): Long?

    @Query(
        """
        SELECT c.*, u.username AS username, u.firstName AS firstName, u.lastName AS lastName
        FROM comment c LEFT JOIN tg_user u ON u.userId = c.userId
        WHERE c.postChatId = :postChatId AND c.postId = :postId AND c.deleted = 0
        ORDER BY c.messageId
        """,
    )
    fun observeForPost(postChatId: Long, postId: Long): Flow<List<CommentRow>>

    @Query("SELECT * FROM comment WHERE postChatId = :postChatId AND postId = :postId AND deleted = 0 ORDER BY messageId")
    suspend fun forPost(postChatId: Long, postId: Long): List<CommentEntity>

    @Query(
        """
        SELECT c.*, u.username AS username, u.firstName AS firstName, u.lastName AS lastName
        FROM comment c LEFT JOIN tg_user u ON u.userId = c.userId
        WHERE c.userId = :userId AND c.deleted = 0
        ORDER BY c.date DESC
        LIMIT :limit
        """,
    )
    fun observeForUser(userId: Long, limit: Int): Flow<List<CommentRow>>

    @Query(
        """
        SELECT c.*, u.username AS username, u.firstName AS firstName, u.lastName AS lastName
        FROM comment c
        JOIN comment_fts f ON c.rowid = f.rowid
        LEFT JOIN tg_user u ON u.userId = c.userId
        WHERE f.text MATCH :match AND c.deleted = 0
          AND (:postChatId = 0 OR c.postChatId = :postChatId)
        ORDER BY c.date DESC
        LIMIT :limit
        """,
    )
    suspend fun search(match: String, postChatId: Long, limit: Int): List<CommentRow>

    @Query("UPDATE comment SET deleted = 1 WHERE chatId = :chatId AND messageId IN (:ids)")
    suspend fun markDeleted(chatId: Long, ids: List<Long>)

    @Query("UPDATE comment SET mediaPath = :path WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun setMediaPath(chatId: Long, messageId: Long, path: String)

    @Query("SELECT COUNT(*) FROM comment WHERE postChatId = :postChatId AND deleted = 0")
    fun observeCount(postChatId: Long): Flow<Int>

    @Query("DELETE FROM comment WHERE postChatId = :postChatId")
    suspend fun deleteForChannel(postChatId: Long)
}

@Dao
interface UserDao {
    @Upsert
    suspend fun upsert(users: List<UserEntity>)

    @Query("SELECT * FROM tg_user WHERE userId = :userId")
    suspend fun get(userId: Long): UserEntity?

    @Query("SELECT * FROM tg_user WHERE userId = :userId")
    fun observe(userId: Long): Flow<UserEntity?>

    @Query("SELECT userId FROM tg_user WHERE userId IN (:ids)")
    suspend fun existing(ids: List<Long>): List<Long>

    @Query("SELECT * FROM tg_user WHERE userId IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<UserEntity>
}

@Dao
abstract class ReactionDao {
    @Query("DELETE FROM reaction WHERE ownerType = :ownerType AND chatId = :chatId AND messageId = :messageId")
    protected abstract suspend fun clear(ownerType: Int, chatId: Long, messageId: Long)

    @Insert
    protected abstract suspend fun insert(items: List<ReactionEntity>)

    @Transaction
    open suspend fun replace(ownerType: Int, chatId: Long, messageId: Long, items: List<ReactionEntity>) {
        clear(ownerType, chatId, messageId)
        if (items.isNotEmpty()) insert(items)
    }

    @Query("SELECT * FROM reaction WHERE ownerType = :ownerType AND chatId = :chatId AND messageId = :messageId ORDER BY count DESC")
    abstract suspend fun forMessage(ownerType: Int, chatId: Long, messageId: Long): List<ReactionEntity>

    @Query("SELECT * FROM reaction WHERE ownerType = :ownerType AND chatId = :chatId AND messageId = :messageId ORDER BY count DESC")
    abstract fun observeForMessage(ownerType: Int, chatId: Long, messageId: Long): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reaction WHERE ownerType = :ownerType AND chatId = :chatId")
    abstract suspend fun forChat(ownerType: Int, chatId: Long): List<ReactionEntity>

    @Query("DELETE FROM reaction WHERE chatId = :chatId")
    abstract suspend fun deleteForChat(chatId: Long)
}

@Dao
interface StatsDao {
    @Query(
        """
        SELECT date(date, 'unixepoch') AS day, COUNT(*) AS count FROM comment
        WHERE postChatId = :channelId AND deleted = 0 AND date >= :from AND date < :to
        GROUP BY day ORDER BY day
        """,
    )
    suspend fun commentsPerDay(channelId: Long, from: Long, to: Long): List<DayCount>

    @Query(
        """
        SELECT c.userId AS userId, u.username AS username, u.firstName AS firstName, u.lastName AS lastName,
               COUNT(*) AS count
        FROM comment c LEFT JOIN tg_user u ON u.userId = c.userId
        WHERE c.postChatId = :channelId AND c.deleted = 0 AND c.userId IS NOT NULL
          AND c.date >= :from AND c.date < :to
        GROUP BY c.userId ORDER BY count DESC LIMIT :limit
        """,
    )
    suspend fun topUsers(channelId: Long, from: Long, to: Long, limit: Int): List<UserCount>

    /** Reactions on posts published in the period plus on comments written in it. */
    @Query(
        """
        SELECT emoji, SUM(total) AS total FROM (
            SELECT r.emoji AS emoji, r.count AS total FROM reaction r
            JOIN post p ON r.ownerType = 0 AND p.chatId = r.chatId AND p.messageId = r.messageId
            WHERE p.chatId = :channelId AND p.date >= :from AND p.date < :to
            UNION ALL
            SELECT r.emoji AS emoji, r.count AS total FROM reaction r
            JOIN comment c ON r.ownerType = 1 AND c.chatId = r.chatId AND c.messageId = r.messageId
            WHERE c.postChatId = :channelId AND c.date >= :from AND c.date < :to
        ) GROUP BY emoji ORDER BY total DESC LIMIT :limit
        """,
    )
    suspend fun topEmoji(channelId: Long, from: Long, to: Long, limit: Int): List<EmojiCount>

    @Query(
        """
        SELECT p.*, COALESCE((SELECT SUM(r.count) FROM reaction r
            WHERE r.ownerType = 0 AND r.chatId = p.chatId AND r.messageId = p.messageId), 0) AS reactionsTotal
        FROM post p
        WHERE p.chatId = :channelId AND p.deleted = 0 AND p.date >= :from AND p.date < :to
        ORDER BY p.replyCount DESC, reactionsTotal DESC LIMIT :limit
        """,
    )
    suspend fun topPosts(channelId: Long, from: Long, to: Long, limit: Int): List<PostRow>

    @Query("SELECT COUNT(*) FROM comment WHERE postChatId = :channelId AND deleted = 0 AND date >= :from AND date < :to")
    suspend fun commentCount(channelId: Long, from: Long, to: Long): Int

    @Query(
        """
        SELECT COUNT(DISTINCT userId) FROM comment
        WHERE postChatId = :channelId AND deleted = 0 AND userId IS NOT NULL AND date >= :from AND date < :to
        """,
    )
    suspend fun uniqueUsers(channelId: Long, from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM post WHERE chatId = :channelId AND deleted = 0 AND date >= :from AND date < :to")
    suspend fun postCount(channelId: Long, from: Long, to: Long): Int

    @Query(
        """
        SELECT COALESCE(SUM(total), 0) FROM (
            SELECT r.count AS total FROM reaction r
            JOIN post p ON r.ownerType = 0 AND p.chatId = r.chatId AND p.messageId = r.messageId
            WHERE p.chatId = :channelId AND p.date >= :from AND p.date < :to
            UNION ALL
            SELECT r.count AS total FROM reaction r
            JOIN comment c ON r.ownerType = 1 AND c.chatId = r.chatId AND c.messageId = r.messageId
            WHERE c.postChatId = :channelId AND c.date >= :from AND c.date < :to
        )
        """,
    )
    suspend fun reactionCount(channelId: Long, from: Long, to: Long): Int

    @Query("SELECT COALESCE(SUM(views), 0) FROM post WHERE chatId = :channelId AND deleted = 0 AND date >= :from AND date < :to")
    suspend fun viewCount(channelId: Long, from: Long, to: Long): Long

    @Query("SELECT * FROM post WHERE chatId = :channelId AND deleted = 0 AND date >= :from AND date < :to ORDER BY date")
    suspend fun postsIn(channelId: Long, from: Long, to: Long): List<PostEntity>

    @Query(
        """
        SELECT messageId, postId, date, userId, replyToMessageId, text FROM comment
        WHERE postChatId = :channelId AND deleted = 0 AND date >= :from AND date < :to
        """,
    )
    suspend fun commentsIn(channelId: Long, from: Long, to: Long): List<CommentLite>

    @Query(
        """
        SELECT messageId, SUM(count) AS total FROM reaction
        WHERE ownerType = 0 AND chatId = :channelId GROUP BY messageId
        """,
    )
    suspend fun postReactionTotals(channelId: Long): List<IdTotal>

    @Query(
        """
        SELECT userId, MIN(date) AS first FROM comment
        WHERE postChatId = :channelId AND deleted = 0 AND userId IS NOT NULL GROUP BY userId
        """,
    )
    suspend fun firstCommentDates(channelId: Long): List<UserFirst>

    @Query(
        """
        SELECT postId AS messageId, MIN(date) AS total FROM comment
        WHERE postChatId = :channelId AND deleted = 0 GROUP BY postId
        """,
    )
    suspend fun firstCommentPerPost(channelId: Long): List<IdTotalLong>
}

data class IdTotalLong(val messageId: Long, val total: Long)

@Dao
interface SyncRunDao {
    @Insert
    suspend fun insert(run: SyncRunEntity): Long

    @Query(
        """
        UPDATE sync_run SET finishedAt = :finishedAt, status = :status, newPosts = :newPosts,
            updatedPosts = :updatedPosts, newComments = :newComments, error = :error
        WHERE id = :id
        """,
    )
    suspend fun finish(
        id: Long,
        finishedAt: Long,
        status: String,
        newPosts: Int,
        updatedPosts: Int,
        newComments: Int,
        error: String?,
    )

    @Query("SELECT * FROM sync_run ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SyncRunEntity>>

    @Query("SELECT * FROM sync_run WHERE channelChatId = :chatId ORDER BY id DESC LIMIT 1")
    fun observeLast(chatId: Long): Flow<SyncRunEntity?>

    @Query("UPDATE sync_run SET status = 'CANCELLED', finishedAt = :at WHERE status = 'RUNNING'")
    suspend fun cancelDangling(at: Long)
}

@Dao
interface AnalysisDao {
    @Insert
    suspend fun insert(item: AnalysisEntity): Long

    @Query("SELECT * FROM analysis WHERE channelChatId = :chatId ORDER BY id DESC")
    fun observeForChannel(chatId: Long): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analysis WHERE id = :id")
    fun observe(id: Long): Flow<AnalysisEntity?>

    @Query("DELETE FROM analysis WHERE id = :id")
    suspend fun delete(id: Long)
}
