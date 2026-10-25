@file:OptIn(ExperimentalMaterial3Api::class)

package dev.tgscope.ui.post

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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.tgscope.AppGraph
import dev.tgscope.core.database.CommentRow
import dev.tgscope.core.database.ReactionEntity
import dev.tgscope.ui.Routes
import dev.tgscope.ui.common.SectionTitle
import dev.tgscope.ui.common.StatLine
import dev.tgscope.ui.common.compact
import dev.tgscope.ui.common.displayName
import dev.tgscope.ui.common.formatUnix
import dev.tgscope.ui.common.reactionLabel

/** A comment placed in the reply tree. */
data class TreeItem(val row: CommentRow, val depth: Int)

/**
 * Orders comments as a tree: replies follow their parent, depth-first. Comments that reply to
 * something outside the thread (e.g. the auto-forwarded post in the discussion group) are roots.
 */
fun buildTree(rows: List<CommentRow>): List<TreeItem> {
    val ids = rows.mapTo(HashSet()) { it.comment.messageId }
    val children = rows.groupBy { r -> r.comment.replyToMessageId?.takeIf { it in ids } }
    val out = ArrayList<TreeItem>(rows.size)
    val stack = ArrayDeque<TreeItem>()
    children[null].orEmpty().asReversed().forEach { stack.addLast(TreeItem(it, 0)) }
    while (stack.isNotEmpty()) {
        val item = stack.removeLast()
        out += item
        children[item.row.comment.messageId].orEmpty().asReversed().forEach { stack.addLast(TreeItem(it, item.depth + 1)) }
    }
    return out
}

@Composable
fun PostDetailScreen(graph: AppGraph, nav: NavController, chatId: Long, postId: Long) {
    val post by remember(chatId, postId) { graph.db.posts().observe(chatId, postId) }.collectAsStateWithLifecycle(null)
    val reactions by remember(chatId, postId) {
        graph.db.reactions().observeForMessage(ReactionEntity.OWNER_POST, chatId, postId)
    }.collectAsStateWithLifecycle(emptyList())
    val comments by remember(chatId, postId) { graph.db.comments().observeForPost(chatId, postId) }
        .collectAsStateWithLifecycle(emptyList())
    var filter by rememberSaveable { mutableStateOf("") }
    var onlyMedia by rememberSaveable { mutableStateOf(false) }
    var onlyReplies by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    val tree = remember(comments) { buildTree(comments) }
    val shown = remember(tree, filter, onlyMedia, onlyReplies) {
        val q = filter.trim().lowercase()
        tree.filter { item ->
            val c = item.row.comment
            (q.isEmpty() || c.text.lowercase().contains(q) ||
                displayName(item.row.username, item.row.firstName, item.row.lastName, c.userId).lowercase().contains(q)) &&
                (!onlyMedia || c.mediaType != null) &&
                (!onlyReplies || item.depth > 0)
        }
    }
    val flat = filter.isNotBlank() || onlyMedia || onlyReplies

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Пост") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.analysis(chatId, postId)) }) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "LLM-анализ")
                    }
                    post?.let { p ->
                        IconButton(onClick = { uri.openUri(p.link) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Открыть в Telegram")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
        ) {
            post?.let { p ->
                item {
                    Column {
                        Text(formatUnix(p.date), style = MaterialTheme.typography.labelSmall)
                        SelectionContainer {
                            Text(
                                p.text.ifBlank { p.mediaType?.let { "[$it]" } ?: "[без текста]" },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatLine("👁", compact(p.views))
                            StatLine("💬", compact(p.replyCount))
                            StatLine("↗", compact(p.forwards))
                        }
                        if (reactions.isNotEmpty()) {
                            Text(
                                reactions.joinToString("  ") { "${reactionLabel(it.emoji)} ${it.count}" },
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
            item {
                SectionTitle("Комментарии (${comments.size})")
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Фильтр по тексту или автору") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = onlyMedia, onClick = { onlyMedia = !onlyMedia }, label = { Text("С медиа") })
                    FilterChip(selected = onlyReplies, onClick = { onlyReplies = !onlyReplies }, label = { Text("Только ответы") })
                }
            }
            items(shown, key = { "${it.row.comment.chatId}:${it.row.comment.messageId}" }) { item ->
                CommentItem(item, flat) { userId -> nav.navigate(Routes.user(userId)) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun CommentItem(item: TreeItem, flat: Boolean, onUser: (Long) -> Unit) {
    val c = item.row.comment
    val indent = if (flat) 0 else item.depth.coerceAtMost(6) * 14
    Column(Modifier.fillMaxWidth().padding(start = indent.dp, top = 8.dp, bottom = 8.dp)) {
        Row {
            Text(
                displayName(item.row.username, item.row.firstName, item.row.lastName, c.userId),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = c.userId != null) { c.userId?.let(onUser) },
            )
            Text(formatUnix(c.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.depth > 0 && !flat) {
            Text("↳ ответ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer {
            Text(c.text.ifBlank { c.mediaType?.let { "[$it]" } ?: "" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
