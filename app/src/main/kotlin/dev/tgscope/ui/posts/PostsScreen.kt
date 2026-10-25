@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tgscope.ui.posts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.tgscope.AppGraph
import dev.tgscope.core.database.PostRow
import dev.tgscope.domain.stats.StatsRepository
import dev.tgscope.ui.Routes
import dev.tgscope.ui.common.EmptyState
import dev.tgscope.ui.common.StatLine
import dev.tgscope.ui.common.compact
import dev.tgscope.ui.common.formatUnix

private val periods = listOf(0 to "Всё время", 7 to "7 дней", 30 to "30 дней")

@Composable
fun PostsScreen(graph: AppGraph, nav: NavController, chatId: Long) {
    val channel by remember(chatId) { graph.db.channels().observe(chatId) }.collectAsStateWithLifecycle(null)
    var query by rememberSaveable { mutableStateOf("") }
    var withMedia by rememberSaveable { mutableStateOf(false) }
    var withComments by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableStateOf(0) }

    val from = if (days == 0) 0L else StatsRepository.daysAgo(days)
    val posts by remember(chatId, query, withMedia, withComments, days) {
        graph.db.posts().observeFeed(chatId, query.trim(), withMedia, withComments, from, Long.MAX_VALUE, 500)
    }.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(channel?.title ?: "Посты", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.stats(chatId)) }) { Icon(Icons.Filled.BarChart, contentDescription = "Статистика") }
                    IconButton(onClick = { nav.navigate(Routes.analysis(chatId, 0)) }) { Icon(Icons.Filled.AutoAwesome, contentDescription = "LLM-анализ") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Поиск по тексту поста") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = withMedia, onClick = { withMedia = !withMedia }, label = { Text("С медиа") })
                FilterChip(selected = withComments, onClick = { withComments = !withComments }, label = { Text("С комментариями") })
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                periods.forEach { (d, label) ->
                    FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                }
            }
            if (posts.isEmpty()) {
                EmptyState("Постов нет", "Запустите синхронизацию на экране каналов или измените фильтры")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(posts, key = { it.post.messageId }) { row ->
                        PostCard(row) { nav.navigate(Routes.post(chatId, row.post.messageId)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostCard(row: PostRow, onClick: () -> Unit) {
    val p = row.post
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(formatUnix(p.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val text = p.text.ifBlank { p.mediaType?.let { "[$it]" } ?: "[без текста]" }
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatLine("👁", compact(p.views))
                StatLine("💬", compact(p.replyCount))
                StatLine("❤", compact(row.reactionsTotal))
                StatLine("↗", compact(p.forwards))
                if (p.mediaType != null) StatLine("📎", p.mediaType!!)
            }
        }
    }
}
