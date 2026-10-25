@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tgscope.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.tgscope.AppGraph
import dev.tgscope.core.database.CommentRow
import dev.tgscope.ui.Routes
import dev.tgscope.ui.common.EmptyState
import dev.tgscope.ui.user.CommentLine
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(graph: AppGraph, nav: NavController) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CommentRow>>(emptyList()) }

    LaunchedEffect(query) {
        delay(300) // debounce typing
        results = if (query.isBlank()) emptyList() else runCatching { graph.stats.search(query) }.getOrDefault(emptyList())
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Поиск по комментариям") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Слова (поиск по началу слова)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            when {
                query.isBlank() -> EmptyState("Полнотекстовый поиск", "Ищет по всем сохранённым комментариям всех каналов")
                results.isEmpty() -> EmptyState("Ничего не найдено")
                else -> LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    item { Text("Найдено: ${results.size}") }
                    items(results, key = { "${it.comment.chatId}:${it.comment.messageId}" }) { row ->
                        CommentLine(row, showAuthor = true) { nav.navigate(Routes.post(row.comment.postChatId, row.comment.postId)) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
