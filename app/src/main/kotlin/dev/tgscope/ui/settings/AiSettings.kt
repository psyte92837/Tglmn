package dev.tgscope.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.tgscope.AppGraph
import dev.tgscope.core.settings.AppSettings
import dev.tgscope.domain.llm.LlmProviders
import dev.tgscope.domain.llm.Prompts
import kotlinx.coroutines.launch

/** Provider picker with key links, live model list and a connection test. */
@Composable
fun AiSettings(graph: AppGraph, draft: AppSettings, onChange: (AppSettings) -> Unit) {
    val provider = LlmProviders.byId(draft.llmProvider)
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var providerMenu by remember { mutableStateOf(false) }
    var models by remember(provider.id) { mutableStateOf<List<String>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var status by remember(provider.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(provider.name, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                LlmProviders.all.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = {
                            providerMenu = false
                            if (p.id != draft.llmProvider) {
                                onChange(
                                    draft.copy(
                                        llmProvider = p.id,
                                        llmEndpoint = p.baseUrl,
                                        llmModel = p.modelHints.firstOrNull() ?: "",
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
        if (provider.note.isNotBlank()) {
            Text(provider.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        provider.keyUrl?.let { url ->
            TextButton(onClick = { uri.openUri(url) }) {
                Text(if (provider.needsKey) "Где взять API-ключ: $url" else "Скачать: $url")
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (provider.needsKey || provider.id == "custom") {
            OutlinedTextField(
                value = draft.llmApiKeys[provider.id].orEmpty(),
                onValueChange = { onChange(draft.copy(llmApiKeys = draft.llmApiKeys + (provider.id to it.trim()))) },
                label = { Text(if (provider.needsKey) "API-ключ ${provider.name}" else "API-ключ (если нужен)") },
                supportingText = { Text("Хранится зашифрованным только на этом устройстве") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = draft.llmEndpoint,
            onValueChange = { onChange(draft.copy(llmEndpoint = it.trim())) },
            label = { Text("Адрес API (base URL)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Box {
            OutlinedTextField(
                value = draft.llmModel,
                onValueChange = { onChange(draft.copy(llmModel = it.trim())) },
                label = { Text("Модель") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        enabled = !busy && draft.llmEndpoint.isNotBlank(),
                        onClick = {
                            busy = true
                            status = "Загружаем список моделей…"
                            scope.launch {
                                runCatching { graph.analyzer.listModels(draft) }
                                    .onSuccess {
                                        models = it
                                        status = if (it.isEmpty()) "Провайдер не вернул список — введите модель вручную" else "Доступно моделей: ${it.size}"
                                        modelMenu = it.isNotEmpty()
                                    }
                                    .onFailure { status = "Не удалось получить модели: ${it.message}" }
                                busy = false
                            }
                        },
                    ) { Icon(Icons.Filled.Refresh, contentDescription = "Загрузить список моделей") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                models.take(300).forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = { onChange(draft.copy(llmModel = m)); modelMenu = false })
                }
            }
        }
        if (provider.modelHints.isNotEmpty() || models.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (provider.modelHints + models.take(5)).distinct().forEach { m ->
                    FilterChip(selected = draft.llmModel == m, onClick = { onChange(draft.copy(llmModel = m)) }, label = { Text(m) })
                }
            }
        }

        Text("Язык ответа", style = MaterialTheme.typography.labelLarge)
        ChipRow(Prompts.languages, draft.llmLanguage, { it }) { onChange(draft.copy(llmLanguage = it)) }
        Text("Длина ответа (max tokens)", style = MaterialTheme.typography.labelLarge)
        ChipRow(listOf(1024, 2048, 4096, 8192), draft.llmMaxTokens, { it.toString() }) { onChange(draft.copy(llmMaxTokens = it)) }
        Text("Креативность (temperature)", style = MaterialTheme.typography.labelLarge)
        ChipRow(listOf(0f, 0.3f, 0.7f, 1f), draft.llmTemperature, { "%.1f".format(it) }) { onChange(draft.copy(llmTemperature = it)) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !busy && draft.hasLlm,
                onClick = {
                    busy = true
                    status = "Проверяем подключение…"
                    scope.launch {
                        status = runCatching { graph.analyzer.test(draft) }
                            .fold({ "✅ Работает. Ответ модели: ${it.take(60)}" }, { "❌ ${it.message}" })
                        busy = false
                    }
                },
            ) { Text("Проверить подключение") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Перед отправкой имена авторов заменяются псевдонимами. Для полной приватности используйте Ollama или LM Studio в своей сети.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}
