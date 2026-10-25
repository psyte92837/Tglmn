package dev.tgscope.domain.analytics

import dev.tgscope.core.database.PostEntity
import dev.tgscope.core.database.TgDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

data class DayPoint(
    val day: String,
    val posts: Int,
    val views: Long,
    val comments: Int,
    val reactions: Int,
    /** Average engagement rate of posts published that day, %. */
    val er: Double,
)

data class GroupStat(val label: String, val posts: Int, val avgViews: Double, val avgEr: Double)

data class PostScore(val post: PostEntity, val reactions: Int, val er: Double)

data class TagStat(val tag: String, val posts: Int, val avgViews: Double, val avgEr: Double)

data class PostSeo(val post: PostEntity, val score: Int, val tips: List<String>)

data class SeoCheck(val ok: Boolean, val text: String)

data class AudienceStats(
    val uniqueUsers: Int,
    val newUsers: Int,
    val returningUsers: Int,
    /** Share of comments written by the most active 10% of commenters, %. */
    val top10Share: Double,
    /** Share of comments that reply to another comment, %. */
    val replyShare: Double,
    val avgCommentsPerPost: Double,
    /** Median minutes from publication to the first comment. */
    val medianFirstCommentMinutes: Double?,
)

data class ContentReport(
    val from: Long,
    val to: Long,
    val postCount: Int,
    val avgViews: Double,
    val avgEr: Double,
    /** Posts per week over the period. */
    val postsPerWeek: Double,
    val daily: List<DayPoint>,
    /** 7 rows (Mon..Sun) × 24 hours, number of comments. */
    val commentHeatmap: List<List<Int>>,
    /** 7 × 24, number of posts published. */
    val postHeatmap: List<List<Int>>,
    /** Average ER by publication hour (only hours with posts). */
    val erByHour: List<GroupStat>,
    val byMedia: List<GroupStat>,
    val byLength: List<GroupStat>,
    val byWeekday: List<GroupStat>,
    val topByEr: List<PostScore>,
    val audience: AudienceStats,
    val postKeywords: List<Term>,
    val commentKeywords: List<Term>,
    val commentBigrams: List<Term>,
    val hashtags: List<TagStat>,
    val mentions: List<Term>,
    val domains: List<Term>,
    val seoAverage: Int,
    val seoPosts: List<PostSeo>,
    val seoChecklist: List<SeoCheck>,
)

/**
 * Local "dashboard" analytics computed in memory over a period: engagement, best time to post,
 * content formats, audience structure, keywords and a heuristic SEO audit of posts.
 */
class ContentAnalytics(private val db: TgDatabase) {

    suspend fun report(chatId: Long, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): ContentReport =
        withContext(Dispatchers.Default) {
            val stats = db.stats()
            val channel = db.channels().get(chatId)
            val posts = stats.postsIn(chatId, from, to)
            val comments = stats.commentsIn(chatId, from, to)
            val reactionsByPost = stats.postReactionTotals(chatId).associate { it.messageId to it.total }
            val firstSeen = stats.firstCommentDates(chatId).associate { it.userId to it.first }
            val firstCommentByPost = stats.firstCommentPerPost(chatId).associate { it.messageId to it.total }

            fun er(p: PostEntity): Double {
                if (p.views <= 0) return 0.0
                val engaged = (reactionsByPost[p.messageId] ?: 0) + p.replyCount + p.forwards
                return engaged * 100.0 / p.views
            }

            val scored = posts.map { PostScore(it, reactionsByPost[it.messageId] ?: 0, er(it)) }

            // Daily series.
            val postsByDay = scored.groupBy { day(it.post.date, zone) }
            val commentsByDay = comments.groupingBy { day(it.date, zone) }.eachCount()
            val days = generateDays(from, to, zone)
            val daily = days.map { d ->
                val ps = postsByDay[d].orEmpty()
                DayPoint(
                    day = d,
                    posts = ps.size,
                    views = ps.sumOf { it.post.views.toLong() },
                    comments = commentsByDay[d] ?: 0,
                    reactions = ps.sumOf { it.reactions },
                    er = ps.map { it.er }.averageOrZero(),
                )
            }

            // Heatmaps (Mon=0 .. Sun=6).
            val commentHeat = List(7) { IntArray(24) }
            comments.forEach { c -> val (d, h) = dowHour(c.date, zone); commentHeat[d][h]++ }
            val postHeat = List(7) { IntArray(24) }
            posts.forEach { p -> val (d, h) = dowHour(p.date, zone); postHeat[d][h]++ }

            val erByHour = scored.groupBy { dowHour(it.post.date, zone).second }
                .toSortedMap()
                .map { (h, list) -> list.group("%02d:00".format(h)) }

            val weekdayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
            val byWeekday = scored.groupBy { dowHour(it.post.date, zone).first }
                .toSortedMap()
                .map { (d, list) -> list.group(weekdayNames[d]) }

            val byMedia = scored.groupBy { mediaLabel(it.post.mediaType) }
                .map { (label, list) -> list.group(label) }
                .sortedByDescending { it.posts }

            val byLength = scored.groupBy { lengthBucket(it.post.text.length) }
                .toSortedMap()
                .map { (bucket, list) -> list.group(lengthLabels[bucket]) }

            // Audience.
            val commenters = comments.mapNotNull { it.userId }
            val perUser = commenters.groupingBy { it }.eachCount()
            val unique = perUser.size
            val newUsers = perUser.keys.count { (firstSeen[it] ?: Long.MAX_VALUE) >= from }
            val topN = (unique / 10).coerceAtLeast(1)
            val topShare = if (commenters.isEmpty()) 0.0 else
                perUser.values.sortedDescending().take(topN).sum() * 100.0 / commenters.size
            val commentIds = comments.mapTo(HashSet()) { it.messageId }
            val replies = comments.count { it.replyToMessageId != null && it.replyToMessageId in commentIds }
            val delays = posts.mapNotNull { p ->
                firstCommentByPost[p.messageId]?.let { first -> ((first - p.date) / 60.0).takeIf { it >= 0 } }
            }.sorted()
            val audience = AudienceStats(
                uniqueUsers = unique,
                newUsers = newUsers,
                returningUsers = unique - newUsers,
                top10Share = topShare,
                replyShare = if (comments.isEmpty()) 0.0 else replies * 100.0 / comments.size,
                avgCommentsPerPost = if (posts.isEmpty()) 0.0 else comments.size.toDouble() / posts.size,
                medianFirstCommentMinutes = delays.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] },
            )

            // Keywords, hashtags, mentions, domains.
            val postTexts = posts.asSequence().map { it.text }
            val commentTexts = comments.asSequence().map { it.text }
            val postKeywords = TextAnalytics.topWords(postTexts, 30)
            val hashtagStats = scored.flatMap { s -> TextAnalytics.hashtags(s.post.text).map { it to s } }
                .groupBy({ it.first }, { it.second })
                .map { (tag, list) -> TagStat(tag, list.size, list.map { it.post.views.toDouble() }.averageOrZero(), list.map { it.er }.averageOrZero()) }
                .sortedByDescending { it.posts }
                .take(30)
            val mentions = TextAnalytics.countTerms((postTexts + commentTexts).flatMap { TextAnalytics.mentions(it).asSequence() }, 20)
            val domains = TextAnalytics.countTerms((postTexts + commentTexts).flatMap { TextAnalytics.domains(it).asSequence() }, 20)

            // SEO audit.
            val keywordSet = postKeywords.take(15).map { it.text }.toSet()
            val seo = posts.map { seoScore(it, keywordSet) }
            val weeks = ((to - from) / (7 * 86_400.0)).coerceAtLeast(1.0 / 7)
            val checklist = channelChecklist(channel?.title.orEmpty(), channel?.username.orEmpty(), posts, seo, keywordSet, posts.size / weeks)

            ContentReport(
                from = from,
                to = to,
                postCount = posts.size,
                avgViews = posts.map { it.views.toDouble() }.averageOrZero(),
                avgEr = scored.map { it.er }.averageOrZero(),
                postsPerWeek = posts.size / weeks,
                daily = daily,
                commentHeatmap = commentHeat.map { it.toList() },
                postHeatmap = postHeat.map { it.toList() },
                erByHour = erByHour,
                byMedia = byMedia,
                byLength = byLength,
                byWeekday = byWeekday,
                topByEr = scored.filter { it.post.views > 0 }.sortedByDescending { it.er }.take(10),
                audience = audience,
                postKeywords = postKeywords,
                commentKeywords = TextAnalytics.topWords(commentTexts, 30),
                commentBigrams = TextAnalytics.topBigrams(commentTexts, 20),
                hashtags = hashtagStats,
                mentions = mentions,
                domains = domains,
                seoAverage = seo.map { it.score.toDouble() }.averageOrZero().roundToInt(),
                seoPosts = seo.sortedBy { it.score }.take(30),
                seoChecklist = checklist,
            )
        }

    companion object {
        private val lengthLabels = listOf("без текста", "до 200", "200–500", "500–1000", "1000+")
        private val ctaRegex = Regex(
            "(?i)\\?|подпис|коммент|напишите|делитесь|поделитесь|переходите|ссылк|голосуй|опрос|как думаете|ваше мнение|" +
                "subscribe|comment|share|vote|what do you think|link",
        )

        fun day(seconds: Long, zone: ZoneId): String = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate().toString()

        fun dowHour(seconds: Long, zone: ZoneId): Pair<Int, Int> {
            val t = Instant.ofEpochSecond(seconds).atZone(zone)
            return (t.dayOfWeek.value - 1) to t.hour
        }

        fun generateDays(from: Long, to: Long, zone: ZoneId): List<String> {
            val start = Instant.ofEpochSecond(from).atZone(zone).toLocalDate()
            val end = Instant.ofEpochSecond((to - 1).coerceAtLeast(from)).atZone(zone).toLocalDate()
            val out = ArrayList<String>()
            var d = start
            while (!d.isAfter(end) && out.size < 3660) {
                out += d.toString()
                d = d.plusDays(1)
            }
            return out
        }

        fun lengthBucket(length: Int): Int = when {
            length == 0 -> 0
            length < 200 -> 1
            length < 500 -> 2
            length < 1000 -> 3
            else -> 4
        }

        fun mediaLabel(type: String?): String = when (type) {
            null -> "текст"
            "photo" -> "фото"
            "video" -> "видео"
            "animation" -> "GIF"
            "document" -> "файл"
            "audio" -> "аудио"
            "voice" -> "голосовое"
            "video_note" -> "кружок"
            "poll" -> "опрос"
            else -> type
        }

        private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()

        private fun List<PostScore>.group(label: String) = GroupStat(
            label = label,
            posts = size,
            avgViews = map { it.post.views.toDouble() }.averageOrZero(),
            avgEr = map { it.er }.averageOrZero(),
        )

        /** Heuristic SEO score of a post (0–100) with actionable tips. */
        fun seoScore(post: PostEntity, keywords: Set<String>): PostSeo {
            val text = post.text.trim()
            val tips = ArrayList<String>()
            if (text.isEmpty()) {
                return PostSeo(post, if (post.mediaType != null) 10 else 0, listOf("Нет текста: добавьте подпись с ключевыми словами — без неё пост не находится поиском"))
            }
            var score = 0
            val firstLine = text.lineSequence().first().trim()
            if (firstLine.length in 20..100) score += 20 else tips += "Первая строка ${firstLine.length} симв. — оптимально 20–100: она видна в превью и поиске"
            if (text.length >= 100) score += 15 else tips += "Короткий текст (${text.length} симв.) хуже находится поиском"
            val tags = TextAnalytics.hashtags(text).size
            when {
                tags in 1..5 -> score += 15
                tags == 0 -> tips += "Добавьте 1–3 тематических хэштега"
                else -> tips += "Слишком много хэштегов ($tags) — оставьте до 5"
            }
            val firstWords = TextAnalytics.words(firstLine).toSet()
            if (keywords.isEmpty() || firstWords.any { it in keywords }) score += 20 else tips += "В первой строке нет ключевых слов канала (${keywords.take(3).joinToString()})"
            if (TextAnalytics.hasLink(text) || TextAnalytics.mentions(text).isNotEmpty()) score += 10 else tips += "Нет ссылок или упоминаний — внутренняя перелинковка помогает росту"
            if (ctaRegex.containsMatchIn(text)) score += 10 else tips += "Нет призыва к действию: задайте вопрос аудитории"
            if (text.length < 300 || text.contains("\n\n") || text.lines().size >= 3) score += 10 else tips += "Разбейте длинный текст на абзацы"
            return PostSeo(post, score, tips)
        }

        fun channelChecklist(
            title: String,
            username: String,
            posts: List<PostEntity>,
            seo: List<PostSeo>,
            keywords: Set<String>,
            postsPerWeek: Double,
        ): List<SeoCheck> {
            val titleWords = TextAnalytics.words(title).toSet()
            val top = keywords.take(5)
            val withTags = posts.count { TextAnalytics.hashtags(it.text).isNotEmpty() }
            val goodFirst = seo.count { s -> s.tips.none { it.startsWith("Первая строка") } && s.post.text.isNotBlank() }
            val share = { n: Int -> if (posts.isEmpty()) 0 else n * 100 / posts.size }
            return listOf(
                SeoCheck(
                    titleWords.any { it in keywords },
                    if (titleWords.any { it in keywords }) "Название канала содержит ключевое слово" else "В названии канала нет главных ключевых слов (${top.joinToString()}) — их учитывает поиск Telegram",
                ),
                SeoCheck(
                    username.length in 5..20 && !username.any(Char::isDigit),
                    "Username @$username: " + if (username.length in 5..20 && !username.any(Char::isDigit)) "короткий и запоминающийся" else "лучше короче и без цифр",
                ),
                SeoCheck(share(withTags) >= 50, "Хэштеги есть в ${share(withTags)}% постов"),
                SeoCheck(share(goodFirst) >= 60, "Удачная первая строка у ${share(goodFirst)}% постов"),
                SeoCheck(postsPerWeek >= 3, "Частота публикаций: %.1f в неделю".format(postsPerWeek) + if (postsPerWeek < 3) " — регулярность ниже рекомендуемой (3+)" else ""),
            )
        }
    }
}
