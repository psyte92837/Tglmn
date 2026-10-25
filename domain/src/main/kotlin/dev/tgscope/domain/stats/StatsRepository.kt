package dev.tgscope.domain.stats

import dev.tgscope.core.database.CommentRow
import dev.tgscope.core.database.DayCount
import dev.tgscope.core.database.EmojiCount
import dev.tgscope.core.database.PostRow
import dev.tgscope.core.database.TgDatabase
import dev.tgscope.core.database.UserCount
import java.time.LocalDate
import java.time.ZoneOffset

data class PeriodSummary(
    val posts: Int,
    val comments: Int,
    val uniqueUsers: Int,
    val reactions: Int,
    val views: Long,
)

data class Comparison(val current: PeriodSummary, val previous: PeriodSummary) {
    fun delta(selector: (PeriodSummary) -> Number): Double? {
        val before = selector(previous).toDouble()
        val now = selector(current).toDouble()
        if (before == 0.0) return null
        return (now - before) / before * 100.0
    }
}

data class ChannelStats(
    val from: Long,
    val to: Long,
    val perDay: List<DayCount>,
    val topUsers: List<UserCount>,
    val topEmoji: List<EmojiCount>,
    val topPosts: List<PostRow>,
    val comparison: Comparison,
)

/** Aggregates over the local dataset; the mobile counterpart of the dashboard and `tg compare`. */
class StatsRepository(private val db: TgDatabase) {

    suspend fun channelStats(chatId: Long, from: Long, to: Long): ChannelStats {
        val stats = db.stats()
        val length = to - from
        return ChannelStats(
            from = from,
            to = to,
            perDay = fillDays(stats.commentsPerDay(chatId, from, to), from, to),
            topUsers = stats.topUsers(chatId, from, to, 10),
            topEmoji = stats.topEmoji(chatId, from, to, 10),
            topPosts = stats.topPosts(chatId, from, to, 10),
            comparison = Comparison(
                current = summary(chatId, from, to),
                previous = summary(chatId, from - length, from),
            ),
        )
    }

    /** Exclusive `to`, like `tg compare --to`. */
    suspend fun summary(chatId: Long, from: Long, to: Long): PeriodSummary {
        val s = db.stats()
        return PeriodSummary(
            posts = s.postCount(chatId, from, to),
            comments = s.commentCount(chatId, from, to),
            uniqueUsers = s.uniqueUsers(chatId, from, to),
            reactions = s.reactionCount(chatId, from, to),
            views = s.viewCount(chatId, from, to),
        )
    }

    suspend fun search(query: String, chatId: Long = 0, limit: Int = 200): List<CommentRow> {
        val match = ftsQuery(query) ?: return emptyList()
        return db.comments().search(match, chatId, limit)
    }

    companion object {
        /** Turns free text into a safe FTS4 prefix query: `foo bar` → `"foo"* "bar"*`. */
        fun ftsQuery(input: String): String? {
            val terms = input.split(Regex("\\s+"))
                .map { it.replace("\"", "").replace("*", "") }
                .filter { it.isNotBlank() }
            if (terms.isEmpty()) return null
            return terms.joinToString(" ") { "\"$it\"*" }
        }

        /** Adds zero rows for days without comments so charts have a continuous axis. */
        fun fillDays(rows: List<DayCount>, from: Long, to: Long): List<DayCount> {
            val byDay = rows.associateBy { it.day }
            val start = LocalDate.ofEpochDay(Math.floorDiv(from, 86_400L))
            val end = LocalDate.ofEpochDay(Math.floorDiv(to - 1, 86_400L))
            if (end.isBefore(start)) return rows
            val out = ArrayList<DayCount>()
            var day = start
            while (!day.isAfter(end)) {
                val key = day.toString()
                out += byDay[key] ?: DayCount(key, 0)
                day = day.plusDays(1)
            }
            return out
        }

        fun daysAgo(days: Int): Long =
            LocalDate.now(ZoneOffset.UTC).minusDays(days.toLong() - 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)

        fun tomorrow(): Long =
            LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
    }
}
