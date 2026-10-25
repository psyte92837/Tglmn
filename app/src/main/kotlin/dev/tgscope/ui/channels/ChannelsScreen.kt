@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tgscope.ui.channels

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.tgscope.AppGraph
import dev.tgscope.core.database.ChannelEntity
import dev.tgscope.domain.export.ExportFormat
import dev.tgscope.domain.export.Exporter
import dev.tgscope.ui.Routes
import dev.tgscope.ui.common.EmptyState
import dev.tgscope.ui.common.formatMillis
import kotlinx.coroutines.launch

@Composable
fun ChannelsScreen(graph: AppGraph, nav: NavController) {
    val channels by remember { graph.db.channels().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val progress by graph.sync.progress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var showAdd by remember { mutableStateOf(false) }
    var scheduleFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var deleteFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var exportTarget by remember { mutableStateOf<ChannelEntity?>(null) }

    fun runExport(uri: Uri?, format: ExportFormat) {
        val channel = exportTarget ?: return
        if (uri == null) return
        graph.appScope.launch {
            val message = runCatching {
                val anonymize = graph.settings.current().anonymizeExport
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    graph.exporter.export(channel.chatId, format, anonymize, out)
                }
            }.fold(
                onSuccess = { "Экспорт готов: ${it.posts} постов, ${it.comments} комментариев" },
                onFailure = { "Ошибка экспорта: ${it.message}" },
            )
            snackbar.showSnackbar(message)
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.JSON.mimeType)) {
        runExport(it, ExportFormat.JSON)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mimeType)) {
        runExport(it, ExportFormat.CSV)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Каналы") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, contentDescription = "Добавить канал") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (channels.isEmpty()) {
            EmptyState(
                "Пока нет каналов",
                "Нажмите +, чтобы добавить публичный канал по @username или ссылке t.me",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(channels, key = { it.chatId }) { channel ->
                    ChannelCard(
                        graph = graph,
                        channel = channel,
                        progress = progress[channel.chatId]?.let { "${stageLabel(it.stage)} ${it.done}/${it.total}" },
                        onOpen = { nav.navigate(Routes.posts(channel.chatId)) },
                        onSync = {
                            graph.scheduler.syncNow(channel.chatId)
                            scope.launch { snackbar.showSnackbar("Синхронизация @${channel.username} запущена") }
                        },
                        onStats = { nav.navigate(Routes.stats(channel.chatId)) },
                        onAnalyze = { nav.navigate(Routes.analysis(channel.chatId, 0)) },
                        onSchedule = { scheduleFor = channel },
                        onExportJson = {
                            exportTarget = channel
                            jsonLauncher.launch(Exporter.fileName(channel.username, ExportFormat.JSON))
                        },
                        onExportCsv = {
                            exportTarget = channel
                            csvLauncher.launch(Exporter.fileName(channel.username, ExportFormat.CSV))
                        },
                        onDelete = { deleteFor = channel },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddChannelDialog(
            onDismiss = { showAdd = false },
            onAdd = { input ->
                runCatching { graph.sync.addChannel(input) }
                    .onSuccess {
                        graph.scheduler.syncNow(it.chatId)
                        showAdd = false
                        snackbar.showSnackbar("Добавлен «${it.title}». Первая выгрузка запущена.")
                    }
                    .exceptionOrNull()?.message
            },
        )
    }

    scheduleFor?.let { channel ->
        ScheduleDialog(
            channel = channel,
            onDismiss = { scheduleFor = null },
            onSave = { enabled, minutes ->
                scope.launch {
                    graph.db.channels().setSchedule(channel.chatId, enabled, minutes)
                    if (enabled) graph.scheduler.schedule(channel.chatId, minutes) else graph.scheduler.cancel(channel.chatId)
                }
                scheduleFor = null
            },
        )
    }

    deleteFor?.let { channel ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Удалить @${channel.username}?") },
            text = { Text("Будут удалены все собранные посты, комментарии и реакции этого канала на устройстве.") },
            confirmButton = {
                TextButton(onClick = {
                    graph.scheduler.cancel(channel.chatId)
                    scope.launch { graph.sync.removeChannel(channel.chatId) }
                    deleteFor = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Отмена") } },
        )
    }
}

private fun stageLabel(stage: String) = when (stage) {
    "scan" -> "Поиск новых постов"
    "posts" -> "Посты"
    "lookback" -> "Обновление старых"
    else -> stage
}

@Composable
private fun ChannelCard(
    graph: AppGraph,
    channel: ChannelEntity,
    progress: String?,
    onOpen: () -> Unit,
    onSync: () -> Unit,
    onStats: () -> Unit,
    onAnalyze: () -> Unit,
    onSchedule: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onDelete: () -> Unit,
) {
    val lastRun by remember(channel.chatId) { graph.db.syncRuns().observeLast(channel.chatId) }
        .collectAsStateWithLifecycle(null)
    val posts by remember(channel.chatId) { graph.db.posts().observeCount(channel.chatId) }.collectAsStateWithLifecycle(0)
    val comments by remember(channel.chatId) { graph.db.comments().observeCount(channel.chatId) }.collectAsStateWithLifecycle(0)
    var menu by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(channel.title, style = MaterialTheme.typography.titleMedium)
                    Text("@${channel.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSync, enabled = progress == null) { Icon(Icons.Filled.Refresh, contentDescription = "Синхронизировать") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Статистика") }, onClick = { menu = false; onStats() })
                    DropdownMenuItem(text = { Text("LLM-анализ канала") }, onClick = { menu = false; onAnalyze() })
                    DropdownMenuItem(text = { Text("Расписание") }, onClick = { menu = false; onSchedule() })
                    DropdownMenuItem(text = { Text("Экспорт JSON") }, onClick = { menu = false; onExportJson() })
                    DropdownMenuItem(text = { Text("Экспорт CSV") }, onClick = { menu = false; onExportCsv() })
                    DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
                }
            }
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                "Постов: $posts · комментариев: $comments" + if (channel.discussionChatId == 0L) " · комментарии выключены" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            val run = lastRun
            Text(
                "Обновлено: ${formatMillis(channel.lastSyncAt)}" +
                    (run?.let { " · ${it.status}" + (it.error?.let { e -> " ($e)" } ?: "") } ?: "") +
                    if (channel.scheduled) " · каждые ${channel.intervalMinutes} мин" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null) {
                Spacer(Modifier.padding(top = 8.dp))
                Text(progress, style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun AddChannelDialog(onDismiss: () -> Unit, onAdd: suspend (String) -> String?) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить канал") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("@username или t.me/…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        error = onAdd(input)
                        busy = false
                    }
                },
            ) { Text(if (busy) "Ищем…" else "Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ScheduleDialog(channel: ChannelEntity, onDismiss: () -> Unit, onSave: (Boolean, Int) -> Unit) {
    var enabled by remember { mutableStateOf(channel.scheduled) }
    var minutes by remember { mutableStateOf(channel.intervalMinutes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Расписание @${channel.username}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Обновлять автоматически", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text("Интервал (Android не позволяет чаще 15 минут):", style = MaterialTheme.typography.bodySmall)
                Row {
                    listOf(15, 30, 60, 180, 720).forEach { m ->
                        FilterChip(
                            selected = minutes == m,
                            onClick = { minutes = m },
                            label = { Text(if (m < 60) "${m}м" else "${m / 60}ч") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.width(1.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(enabled, minutes) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
