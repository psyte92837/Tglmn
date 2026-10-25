@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tgscope.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.tgscope.AppGraph
import dev.tgscope.core.settings.AppSettings
import dev.tgscope.domain.llm.Prompts
import dev.tgscope.ui.Routes
import dev.tgscope.ui.common.SectionTitle
import dev.tgscope.watch.WatchService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(graph: AppGraph, nav: NavController) {
    val saved by graph.settings.settings.collectAsStateWithLifecycle(null)
    val current = saved ?: return
    // Local editable copy; written back with "Сохранить".
    var draft by remember { mutableStateOf(current) }

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmLogout by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun save(updated: AppSettings, message: String = "Сохранено") {
        draft = updated
        scope.launch {
            graph.settings.update { updated }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Настройки") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionTitle("Сбор данных")
            NumberField("Новых постов за запуск (POST_LIMIT)", draft.postLimit) { draft = draft.copy(postLimit = it) }
            NumberField("Перепроверять старых постов (LOOKBACK)", draft.lookbackPosts) { draft = draft.copy(lookbackPosts = it) }
            NumberField("Пауза, сек", draft.pauseSeconds) { draft = draft.copy(pauseSeconds = it) }
            NumberField("Пауза после каждых N постов", draft.pauseAfterPosts) { draft = draft.copy(pauseAfterPosts = it) }
            SwitchRow("Скачивать медиа", draft.downloadMedia) { draft = draft.copy(downloadMedia = it) }
            Text(
                "Не отключайте паузы и не ставьте большой лимит: так меньше риск FloodWait и ограничений аккаунта.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Экспорт")
            SwitchRow("Анонимизировать пользователей", draft.anonymizeExport) { draft = draft.copy(anonymizeExport = it) }
            Text(
                "id, username и имена заменяются стабильными псевдонимами. Экспорт запускается из меню канала.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Watch mode")
            SwitchRow("Следить за каналами в реальном времени", current.watchEnabled) { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (enabled) WatchService.start(context) else WatchService.stop(context)
                save(draft.copy(watchEnabled = enabled), if (enabled) "Watch включён" else "Watch выключен")
            }
            Text(
                "Держит постоянное уведомление и расходует батарею. Android 15 ограничивает такие службы ~6 часами в сутки; " +
                    "для надёжного обновления включите ещё и расписание у канала.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("LLM-анализ")
            OutlinedTextField(
                value = draft.llmEndpoint,
                onValueChange = { draft = draft.copy(llmEndpoint = it) },
                label = { Text("Endpoint (OpenAI-совместимый), напр. https://api.openai.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.llmModel,
                onValueChange = { draft = draft.copy(llmModel = it) },
                label = { Text("Модель") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = draft.llmApiKey,
                onValueChange = { draft = draft.copy(llmApiKey = it) },
                label = { Text("API-ключ (хранится зашифрованным)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Prompts.languages.forEach { lang ->
                    FilterChip(selected = draft.llmLanguage == lang, onClick = { draft = draft.copy(llmLanguage = lang) }, label = { Text(lang) })
                }
            }
            Text(
                "Для полной локальности укажите Ollama или LM Studio в своей сети, например http://192.168.1.10:11434/v1. " +
                    "Перед отправкой авторы заменяются псевдонимами.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(onClick = { save(draft) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Сохранить") }

            SectionTitle("Служебное")
            OutlinedButton(onClick = { nav.navigate(Routes.HISTORY) }, modifier = Modifier.fillMaxWidth()) { Text("История запусков") }
            OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Выйти из Telegram") }
            Text(
                "Собранные данные остаются на устройстве после выхода. Чтобы удалить их, удалите каналы.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Сессия TDLib будет закрыта. Расписания и Watch перестанут работать до нового входа.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    WatchService.stop(context)
                    graph.appScope.launch { runCatching { graph.telegram.logOut() } }
                }) { Text("Выйти") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(4)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
