@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tgscope.ui.user

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.tgscope.AppGraph
import dev.tgscope.core.database.CommentRow
import dev.tgscope.ui.Routes
import dev.tgscope.ui.common.SectionTitle
import dev.tgscope.ui.common.displayName
import dev.tgscope.ui.common.formatUnix

@Composable
fun UserScreen(graph: AppGraph, nav: NavController, userId: Long) {
    val user by remember(userId) { graph.db.users().observe(userId) }.collectAsStateWithLifecycle(null)
    val comments by remember(userId) { graph.db.comments().observeForUser(userId, 500) }.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp)) {
            item {
                val u = user
                Column {
                    Text(displayName(u?.username, u?.firstName, u?.lastName, userId), style = MaterialTheme.typography.titleLarge)
                    Text("id $userId", style = MaterialTheme.typography.bodySmall)
                    if (u != null) {
                        Text(
                            listOfNotNull(if (u.isBot) "бот" else null, if (u.isPremium) "Premium" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val first = comments.minOfOrNull { it.comment.date }
                    val last = comments.maxOfOrNull { it.comment.date }
                    Text(
                        "Комментариев в базе: ${comments.size}" +
                            if (first != null && last != null) " · с ${formatUnix(first)} по ${formatUnix(last)}" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    SectionTitle("Комментарии")
                }
            }
            items(comments, key = { "${it.comment.chatId}:${it.comment.messageId}" }) { row ->
                CommentLine(row) { nav.navigate(Routes.post(row.comment.postChatId, row.comment.postId)) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun CommentLine(row: CommentRow, showAuthor: Boolean = false, onClick: () -> Unit) {
    val c = row.comment
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(
            (if (showAuthor) displayName(row.username, row.firstName, row.lastName, c.userId) + " · " else "") + formatUnix(c.date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            c.text.ifBlank { c.mediaType?.let { "[$it]" } ?: "" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
