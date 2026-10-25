@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tgscope.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.tgscope.AppGraph
import dev.tgscope.ui.common.EmptyState
import dev.tgscope.ui.common.formatMillis

@Composable
fun HistoryScreen(graph: AppGraph, nav: NavController) {
    val runs by remember { graph.db.syncRuns().observeRecent(200) }.collectAsStateWithLifecycle(emptyList())
    val channels by remember { graph.db.channels().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val names = channels.associate { it.chatId to "@${it.username}" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("История запусков") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        if (runs.isEmpty()) {
            EmptyState("Запусков ещё не было", modifier = Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp)) {
                items(runs, key = { it.id }) { run ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            "${run.status} · ${names[run.channelChatId] ?: run.channelChatId} · ${run.mode}",
                            style = MaterialTheme.typography.titleSmall,
                            color = when (run.status) {
                                "SUCCESS" -> Color(0xFF2E7D32)
                                "FAILED", "CANCELLED" -> MaterialTheme.colorScheme.error
                                "PARTIAL" -> Color(0xFFEF6C00)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        val duration = run.finishedAt?.let { " · ${(it - run.startedAt) / 1000} с" } ?: ""
                        Text("${formatMillis(run.startedAt)}$duration", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "новых постов ${run.newPosts}, обновлено ${run.updatedPosts}, новых комментариев ${run.newComments}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        run.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
