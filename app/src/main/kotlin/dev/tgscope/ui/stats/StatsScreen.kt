@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tgscope.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.tgscope.AppGraph
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import dev.tgscope.domain.analytics.ContentReport
import dev.tgscope.domain.stats.ChannelStats
import dev.tgscope.domain.stats.Comparison
import dev.tgscope.domain.stats.PeriodSummary
import dev.tgscope.domain.stats.StatsRepository
import dev.tgscope.ui.Routes
import dev.tgscope.ui.common.DailyBarChart
import dev.tgscope.ui.common.EmptyState
import dev.tgscope.ui.common.HorizontalBar
import dev.tgscope.ui.common.Loading
import dev.tgscope.ui.common.SectionTitle
import dev.tgscope.ui.common.compact
import dev.tgscope.ui.common.displayName
import dev.tgscope.ui.common.formatDateUnix
import dev.tgscope.ui.common.reactionLabel

private val periods = listOf(7, 30, 90, 365)
private val statTabs = listOf("Обзор", "Активность", "Контент", "SEO и ключи", "Аудитория")

@Composable
fun StatsScreen(graph: AppGraph, nav: NavController, initialChatId: Long) {
    val channels by remember { graph.db.channels().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf(initialChatId) }
    var days by rememberSaveable { mutableStateOf(30) }
    var picker by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }

    val chatId = if (selected != 0L) selected else channels.firstOrNull()?.chatId ?: 0L
    val stats by produceState<ChannelStats?>(null, chatId, days) {
        value = null
        if (chatId != 0L) value = graph.stats.channelStats(chatId, StatsRepository.daysAgo(days), StatsRepository.tomorrow())
    }
    val report by produceState<ContentReport?>(null, chatId, days, tab != 0) {
        value = null
        if (chatId != 0L && tab != 0) {
            value = graph.content.report(chatId, StatsRepository.daysAgo(days), StatsRepository.tomorrow())
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Статистика") }) }) { padding ->
        if (channels.isEmpty()) {
            EmptyState("Нет данных", "Добавьте канал и выполните синхронизацию", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp)) {
            item {
                Box {
                    OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(channels.firstOrNull { it.chatId == chatId }?.let { "${it.title} (@${it.username})" } ?: "Выберите канал")
                    }
                    DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                        channels.forEach { ch ->
                            DropdownMenuItem(text = { Text(ch.title) }, onClick = { selected = ch.chatId; picker = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    periods.forEach { d ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d дн.") })
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    statTabs.forEachIndexed { i, label ->
                        FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                    }
                }
            }
            val s = stats
            val r = report
            if (tab != 0) {
                if (r == null) {
                    item { Box(Modifier.fillMaxWidth().padding(32.dp)) { Loading() } }
                } else {
                    when (tab) {
                        1 -> activityTab(r)
                        2 -> contentTab(r) { nav.navigate(Routes.post(it.chatId, it.messageId)) }
                        3 -> seoTab(r) { nav.navigate(Routes.post(it.chatId, it.messageId)) }
                        else -> audienceTab(r)
                    }
                }
            } else if (s == null) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp)) { Loading() } }
            } else {
                item { ComparisonBlock(s.comparison, days) }
                item {
                    SectionTitle("Комментарии по дням")
                    DailyBarChart(
                        labels = s.perDay.map { it.day.substring(5) },
                        values = s.perDay.map { it.count },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { SectionTitle("Самые активные комментаторы") }
                val maxUser = s.topUsers.maxOfOrNull { it.count } ?: 1
                items(s.topUsers, key = { "u${it.userId}" }) { u ->
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.user(u.userId)) }.padding(vertical = 6.dp)) {
                        Row {
                            Text(displayName(u.username, u.firstName, u.lastName, u.userId), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(u.count.toString())
                        }
                        HorizontalBar(u.count.toFloat() / maxUser, Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
                item { SectionTitle("Топ реакций") }
                val maxEmoji = s.topEmoji.maxOfOrNull { it.total } ?: 1
                items(s.topEmoji, key = { "e${it.emoji}" }) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(reactionLabel(e.emoji), Modifier.width(40.dp))
                        HorizontalBar(e.total.toFloat() / maxEmoji, Modifier.weight(1f).padding(top = 8.dp, end = 8.dp))
                        Text(compact(e.total))
                    }
                }
                item { SectionTitle("Самые обсуждаемые посты") }
                items(s.topPosts, key = { "p${it.post.messageId}" }) { row ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { nav.navigate(Routes.post(row.post.chatId, row.post.messageId)) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(formatDateUnix(row.post.date), style = MaterialTheme.typography.labelSmall)
                            Text(row.post.text.ifBlank { "[${row.post.mediaType ?: "без текста"}]" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("💬 ${row.post.replyCount} · ❤ ${row.reactionsTotal} · 👁 ${compact(row.post.views)}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonBlock(c: Comparison, days: Int) {
    Column(Modifier.padding(top = 12.dp)) {
        Text("Сравнение с предыдущими $days дн.", style = MaterialTheme.typography.titleSmall)
        val metrics: List<Pair<String, (PeriodSummary) -> Number>> = listOf(
            "Посты" to { s: PeriodSummary -> s.posts },
            "Комментарии" to { s: PeriodSummary -> s.comments },
            "Уникальные авторы" to { s: PeriodSummary -> s.uniqueUsers },
            "Реакции" to { s: PeriodSummary -> s.reactions },
            "Просмотры" to { s: PeriodSummary -> s.views },
        )
        metrics.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, selector) ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                            Text(compact(selector(c.current)), style = MaterialTheme.typography.headlineSmall)
                            val delta = c.delta(selector)
                            Text(
                                when {
                                    delta == null -> "было ${compact(selector(c.previous))}"
                                    delta >= 0 -> "▲ %.0f%%".format(delta)
                                    else -> "▼ %.0f%%".format(-delta)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    delta >= 0 -> Color(0xFF2E7D32)
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
