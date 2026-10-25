package dev.tgscope.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.tgscope.AppGraph
import dev.tgscope.core.tdlib.AuthState
import dev.tgscope.ui.common.Loading
import kotlinx.coroutines.launch

@Composable
fun AuthFlow(graph: AppGraph, state: AuthState) {
    when (state) {
        AuthState.Initializing, AuthState.LoggingOut, AuthState.Closed, AuthState.Ready -> Loading()
        AuthState.NeedCredentials -> CredentialsScreen(graph)
        AuthState.WaitPhone -> StepScreen(
            graph = graph,
            title = "Вход в Telegram",
            text = "Номер телефона в международном формате. Лучше использовать отдельный аккаунт: " +
                "Telegram может ограничить аккаунты, которые массово читают каналы.",
            label = "+7 900 000-00-00",
            keyboard = KeyboardType.Phone,
            secret = false,
            action = { graph.telegram.sendPhone(it) },
        )
        is AuthState.WaitCode -> StepScreen(
            graph = graph,
            title = "Код подтверждения",
            text = "Код отправлен в Telegram на ${state.phoneNumber}.",
            label = "Код",
            keyboard = KeyboardType.Number,
            secret = false,
            action = { graph.telegram.sendCode(it) },
        )
        is AuthState.WaitPassword -> StepScreen(
            graph = graph,
            title = "Облачный пароль",
            text = if (state.hint.isNotBlank()) "Подсказка: ${state.hint}" else "Включена двухэтапная проверка.",
            label = "Пароль",
            keyboard = KeyboardType.Password,
            secret = true,
            action = { graph.telegram.sendPassword(it) },
        )
        is AuthState.Unsupported -> Message(
            graph,
            "Этот шаг входа (${state.step}) приложение пока не поддерживает. Войдите в аккаунт в официальном клиенте и попробуйте снова.",
        )
        is AuthState.Error -> Message(graph, "Ошибка TDLib: ${state.message}")
    }
}

@Composable
private fun CredentialsScreen(graph: AppGraph) {
    var apiId by rememberSaveable { mutableStateOf("") }
    var apiHash by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    AuthColumn {
        Text("TGScope", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Локальный сборщик и аналитика комментариев Telegram-каналов. Ключи, сессия и данные " +
                "хранятся только на этом устройстве.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Создайте приложение на my.telegram.org → API development tools и вставьте api_id и api_hash.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { uri.openUri("https://my.telegram.org/apps") }) { Text("Открыть my.telegram.org") }
        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it.filter(Char::isDigit) },
            label = { Text("api_id") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it.trim() },
            label = { Text("api_hash") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val id = apiId.toIntOrNull()
                if (id == null || id <= 0 || apiHash.length < 16) {
                    error = "Проверьте api_id и api_hash"
                    return@Button
                }
                scope.launch {
                    graph.settings.saveApiCredentials(id, apiHash)
                    graph.startTelegram()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Продолжить") }
    }
}

@Composable
private fun StepScreen(
    graph: AppGraph,
    title: String,
    text: String,
    label: String,
    keyboard: KeyboardType,
    secret: Boolean,
    action: suspend (String) -> Unit,
) {
    var value by remember(title) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember(title) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthColumn {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { action(value) }.onFailure { error = it.message }
                    busy = false
                }
            },
            enabled = !busy && value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(2.dp)) else Text("Далее")
        }
        TextButton(onClick = { graph.resetCredentials() }) { Text("Сменить api_id / api_hash") }
    }
}

@Composable
private fun Message(graph: AppGraph, text: String) {
    AuthColumn {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { graph.startTelegram() }) { Text("Повторить") }
        TextButton(onClick = { graph.resetCredentials() }) { Text("Сбросить ключи и сессию") }
    }
}

@Composable
private fun AuthColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
