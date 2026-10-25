package dev.tgscope.ui.common

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TGScopeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8FB6E8), secondary = Color(0xFF9CCBC0))
        else -> lightColorScheme(primary = Color(0xFF1F3A5F), secondary = Color(0xFF2E7D6B))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val dateOnly = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun formatUnix(seconds: Long): String =
    dateTime.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))

fun formatMillis(millis: Long): String =
    if (millis <= 0) "никогда" else dateTime.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun formatDateUnix(seconds: Long): String =
    dateOnly.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()))

fun compact(n: Number): String {
    val v = n.toDouble()
    return when {
        v >= 1_000_000 -> "%.1fM".format(v / 1_000_000)
        v >= 10_000 -> "%.0fK".format(v / 1_000)
        v >= 1_000 -> "%.1fK".format(v / 1_000)
        else -> n.toLong().toString()
    }
}

fun displayName(username: String?, firstName: String?, lastName: String?, userId: Long?): String {
    val name = listOfNotNull(firstName?.takeIf { it.isNotBlank() }, lastName?.takeIf { it.isNotBlank() })
        .joinToString(" ")
    return when {
        name.isNotEmpty() && !username.isNullOrBlank() -> "$name (@$username)"
        name.isNotEmpty() -> name
        !username.isNullOrBlank() -> "@$username"
        userId != null -> "id$userId"
        else -> "Канал"
    }
}

fun reactionLabel(emoji: String): String = when {
    emoji.startsWith("custom:") -> "✨"
    emoji == "paid" -> "⭐"
    else -> emoji
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun StatLine(icon: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("$icon $value", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
