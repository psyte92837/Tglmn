@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tgscope.ui.analysis

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import dev.tgscope.AppGraph
import dev.tgscope.domain.llm.Prompts
import dev.tgscope.domain.stats.StatsRepository
import dev.tgscope.ui.common.SectionTitle
import dev.tgscope.ui.common.formatMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Survives navigation and rotation so a long LLM call is not lost. */
class AnalysisViewModel(private val graph: AppGraph, private val chatId: Long, private val postId: Long) : ViewModel() {
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _selected = MutableStateFlow<Long?>(null)
    val selected = _selected.asStateFlow()

    val history = graph.db.analyses().observeForChannel(chatId)

    fun select(id: Long?) {
        _selected.value = id
    }

    fun run(prompt: String, days: Int) {
        if (_running.value) return
        _running.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching {
                if (postId != 0L) {
                    graph.analyzer.analyzePost(chatId, postId, prompt)
                } else {
                    graph.analyzer.analyzeChannel(chatId, StatsRepository.daysAgo(days), StatsRepository.tomorrow(), prompt)
                }
            }.onSuccess { _selected.value = it.id }
                .onFailure { _error.value = it.message ?: it::class.simpleName }
            _running.value = false
        }
    }
}

@Composable
fun AnalysisScreen(graph: AppGraph, nav: NavController, chatId: Long, postId: Long) {
    val vm: AnalysisViewModel = viewModel(key = "analysis-$chatId-$postId") { AnalysisViewModel(graph, chatId, postId) }
    val running by vm.running.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val all by remember(vm) { vm.history }.collectAsStateWithLifecycle(emptyList())
    val settings by graph.settings.settings.collectAsStateWithLifecycle(null)
    val history = all.filter { it.postId == postId }

    var prompt by rememberSaveable { mutableStateOf(Prompts.names.first()) }
    var days by rememberSaveable { mutableStateOf(7) }
    val language = settings?.llmLanguage ?: "ru"
    val shown = history.firstOrNull { it.id == selectedId } ?: history.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (postId == 0L) "Анализ канала" else "Анализ поста") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp)) {
            item {
                if (settings?.hasLlm == false) {
                    Text(
                        "Сначала укажите endpoint и модель в Настройках → LLM-анализ.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Prompts.names.forEach { name ->
                        FilterChip(selected = prompt == name, onClick = { prompt = name }, label = { Text(Prompts.title(name, language)) })
                    }
                }
                if (postId == 0L) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 7, 30).forEach { d ->
                            FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d дн.") })
                        }
                    }
                }
                Button(
                    onClick = { vm.run(prompt, days) },
                    enabled = !running && settings?.hasLlm == true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(if (running) "Анализируем…" else "Запустить анализ") }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (shown != null) {
                item {
                    SectionTitle("${Prompts.title(shown.promptName, shown.language)} · ${shown.model} · ${formatMillis(shown.createdAt)}")
                    Card(Modifier.fillMaxWidth()) {
                        SelectionContainer {
                            Text(shown.result, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (history.size > 1) {
                item { SectionTitle("Предыдущие анализы") }
                items(history, key = { it.id }) { a ->
                    Column(Modifier.fillMaxWidth().clickable { vm.select(a.id) }.padding(vertical = 8.dp)) {
                        Text("${Prompts.title(a.promptName, a.language)} · ${formatMillis(a.createdAt)}", style = MaterialTheme.typography.labelLarge)
                        Text(a.result, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
