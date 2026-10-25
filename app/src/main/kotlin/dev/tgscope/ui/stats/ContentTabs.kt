package dev.tgscope.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tgscope.core.database.PostEntity
import dev.tgscope.domain.analytics.ContentReport
import dev.tgscope.domain.analytics.GroupStat
import dev.tgscope.domain.analytics.Term
import dev.tgscope.ui.common.BarRow
import dev.tgscope.ui.common.DailyBarChart
import dev.tgscope.ui.common.LineChart
import dev.tgscope.ui.common.SectionTitle
import dev.tgscope.ui.common.WeekHourHeatmap
import dev.tgscope.ui.common.compact
import dev.tgscope.ui.common.formatDateUnix

private fun pct(v: Double) = "%.1f%%".format(v)

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun KpiRow(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (label, value) ->
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(10.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Text(value, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun GroupBars(groups: List<GroupStat>, byEr: Boolean) {
    val max = groups.maxOfOrNull { if (byEr) it.avgEr else it.avgViews } ?: 0.0
    groups.forEach { g ->
        val v = if (byEr) g.avgEr else g.avgViews
        BarRow(
            label = "${g.label} · ${g.posts} пост.",
            value = v,
            max = max,
            valueText = if (byEr) "ER ${pct(v)}" else "👁 ${compact(v)}",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TermCloud(terms: List<Term>) {
    if (terms.isEmpty()) {
        Hint("Недостаточно данных")
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        terms.forEach { t -> AssistChip(onClick = {}, label = { Text("${t.text} · ${t.count}") }) }
    }
}

// ---------------------------------------------------------------- Активность

fun LazyListScope.activityTab(r: ContentReport) {
    item {
        KpiRow("Постов" to r.postCount.toString(), "В неделю" to "%.1f".format(r.postsPerWeek), "Ср. охват" to compact(r.avgViews))
        KpiRow("Ср. ER" to pct(r.avgEr), "Реакция" to (r.audience.medianFirstCommentMinutes?.let { "%.0f мин".format(it) } ?: "—"))
        Hint("ER = (реакции + комментарии + репосты) / просмотры. «Реакция» — медианное время до первого комментария.")
    }
    item {
        SectionTitle("Когда комментируют (день × час)")
        WeekHourHeatmap(r.commentHeatmap, Modifier.fillMaxWidth())
        Hint("Тёмные клетки — пики активности аудитории: публикуйте незадолго до них.")
    }
    item {
        SectionTitle("Когда вы публикуете")
        WeekHourHeatmap(r.postHeatmap, Modifier.fillMaxWidth())
    }
    item {
        SectionTitle("ER по часу публикации")
        if (r.erByHour.isEmpty()) Hint("Нет постов за период") else GroupBars(r.erByHour, byEr = true)
        val best = r.erByHour.filter { it.posts >= 2 }.maxByOrNull { it.avgEr }
        best?.let { Hint("Лучшее время по вовлечённости: ${it.label} (ER ${pct(it.avgEr)}, ${it.posts} пост.)") }
    }
    item {
        SectionTitle("ER по дням недели")
        GroupBars(r.byWeekday, byEr = true)
    }
    item {
        SectionTitle("Просмотры по дням")
        LineChart(r.daily.map { it.views.toDouble() }, r.daily.map { it.day.substring(5) }, Modifier.fillMaxWidth()) { compact(it) }
    }
    item {
        SectionTitle("Вовлечённость (ER) по дням")
        LineChart(r.daily.map { it.er }, r.daily.map { it.day.substring(5) }, Modifier.fillMaxWidth()) { pct(it) }
    }
    item {
        SectionTitle("Публикации по дням")
        DailyBarChart(r.daily.map { it.day.substring(5) }, r.daily.map { it.posts }, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- Контент

fun LazyListScope.contentTab(r: ContentReport, onPost: (PostEntity) -> Unit) {
    item {
        SectionTitle("Форматы: средний ER")
        GroupBars(r.byMedia, byEr = true)
        SectionTitle("Форматы: средний охват")
        GroupBars(r.byMedia, byEr = false)
    }
    item {
        SectionTitle("Длина текста: средний ER")
        GroupBars(r.byLength, byEr = true)
        SectionTitle("Длина текста: средний охват")
        GroupBars(r.byLength, byEr = false)
    }
    item { SectionTitle("Посты с лучшей вовлечённостью") }
    items(r.topByEr, key = { "er${it.post.messageId}" }) { s ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPost(s.post) }) {
            Column(Modifier.padding(12.dp)) {
                Text("${formatDateUnix(s.post.date)} · ER ${pct(s.er)}", style = MaterialTheme.typography.labelSmall)
                Text(s.post.text.ifBlank { "[${s.post.mediaType ?: "без текста"}]" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("👁 ${compact(s.post.views)} · ❤ ${s.reactions} · 💬 ${s.post.replyCount} · ↗ ${s.post.forwards}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ---------------------------------------------------------------- SEO

fun LazyListScope.seoTab(r: ContentReport, onPost: (PostEntity) -> Unit) {
    item {
        KpiRow("SEO-оценка постов" to "${r.seoAverage}/100", "Постов" to r.postCount.toString())
        SectionTitle("Чек-лист канала")
        r.seoChecklist.forEach { c ->
            Text(
                (if (c.ok) "✅ " else "⚠️ ") + c.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (c.ok) MaterialTheme.colorScheme.onSurface else Color(0xFFEF6C00),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
    item {
        SectionTitle("Ключевые слова постов")
        TermCloud(r.postKeywords)
        SectionTitle("О чём говорят в комментариях")
        TermCloud(r.commentKeywords)
        SectionTitle("Частые словосочетания в комментариях")
        TermCloud(r.commentBigrams)
    }
    item {
        SectionTitle("Хэштеги: охват и ER")
        if (r.hashtags.isEmpty()) {
            Hint("Хэштегов в постах нет — добавьте 1–3 тематических, это помогает поиску внутри Telegram")
        } else {
            val max = r.hashtags.maxOf { it.avgEr }
            r.hashtags.forEach { t -> BarRow("${t.tag} · ${t.posts} пост. · 👁 ${compact(t.avgViews)}", t.avgEr, max, "ER ${pct(t.avgEr)}") }
        }
    }
    item {
        SectionTitle("Упоминания (@)")
        TermCloud(r.mentions)
        SectionTitle("Домены ссылок")
        TermCloud(r.domains)
    }
    item {
        SectionTitle("Посты, которые стоит доработать")
        Hint("Оценка учитывает первую строку, длину, хэштеги, ключевые слова, ссылки, призыв к действию и структуру.")
    }
    items(r.seoPosts, key = { "seo${it.post.messageId}" }) { s ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPost(s.post) }) {
            Column(Modifier.padding(12.dp)) {
                Text("${formatDateUnix(s.post.date)} · SEO ${s.score}/100", style = MaterialTheme.typography.labelSmall)
                Text(s.post.text.ifBlank { "[${s.post.mediaType ?: "без текста"}]" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                s.tips.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ---------------------------------------------------------------- Аудитория

fun LazyListScope.audienceTab(r: ContentReport) {
    val a = r.audience
    item {
        KpiRow("Авторов" to a.uniqueUsers.toString(), "Новых" to a.newUsers.toString(), "Вернувшихся" to a.returningUsers.toString())
        KpiRow("Коммент./пост" to "%.1f".format(a.avgCommentsPerPost), "Ответы" to pct(a.replyShare), "Топ-10%" to pct(a.top10Share))
        Hint(
            "«Новые» впервые прокомментировали в этом периоде. «Ответы» — доля комментариев, отвечающих другим участникам " +
                "(признак живой дискуссии). «Топ-10%» — какую долю комментариев пишут самые активные 10% авторов: " +
                "чем выше, тем сильнее обсуждение держится на ядре.",
        )
    }
    item {
        SectionTitle("Новые и вернувшиеся")
        val total = (a.newUsers + a.returningUsers).toDouble().coerceAtLeast(1.0)
        BarRow("Новые", a.newUsers.toDouble(), total, pct(a.newUsers * 100 / total))
        BarRow("Вернувшиеся", a.returningUsers.toDouble(), total, pct(a.returningUsers * 100 / total))
    }
    item {
        SectionTitle("Комментарии по дням")
        DailyBarChart(r.daily.map { it.day.substring(5) }, r.daily.map { it.comments }, Modifier.fillMaxWidth())
    }
}
