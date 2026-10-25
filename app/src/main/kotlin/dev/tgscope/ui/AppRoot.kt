package dev.tgscope.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.tgscope.AppGraph
import dev.tgscope.core.tdlib.AuthState
import dev.tgscope.ui.analysis.AnalysisScreen
import dev.tgscope.ui.auth.AuthFlow
import dev.tgscope.ui.channels.ChannelsScreen
import dev.tgscope.ui.history.HistoryScreen
import dev.tgscope.ui.post.PostDetailScreen
import dev.tgscope.ui.posts.PostsScreen
import dev.tgscope.ui.search.SearchScreen
import dev.tgscope.ui.settings.SettingsScreen
import dev.tgscope.ui.stats.StatsScreen
import dev.tgscope.ui.user.UserScreen

@Composable
fun AppRoot(graph: AppGraph) {
    val auth by graph.telegram.authState.collectAsStateWithLifecycle()
    if (auth == AuthState.Ready) MainNav(graph) else AuthFlow(graph, auth)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.CHANNELS, "Каналы", Icons.Filled.Campaign),
    Tab(Routes.SEARCH, "Поиск", Icons.Filled.Search),
    Tab(Routes.STATS, "Статистика", Icons.Filled.BarChart),
    Tab(Routes.SETTINGS, "Настройки", Icons.Filled.Settings),
)

object Routes {
    const val CHANNELS = "channels"
    const val SEARCH = "search"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val HISTORY = "history"

    fun posts(chatId: Long) = "posts/$chatId"
    fun post(chatId: Long, postId: Long) = "post/$chatId/$postId"
    fun user(userId: Long) = "user/$userId"
    fun analysis(chatId: Long, postId: Long) = "analysis/$chatId/$postId"
    fun stats(chatId: Long) = "stats?chatId=$chatId"
}

@Composable
private fun MainNav(graph: AppGraph) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { route?.startsWith(it.route) == true }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route?.startsWith(tab.route) == true,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.CHANNELS, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Routes.CHANNELS) { ChannelsScreen(graph, nav) }
            composable(Routes.SEARCH) { SearchScreen(graph, nav) }
            composable(
                "${Routes.STATS}?chatId={chatId}",
                arguments = listOf(navArgument("chatId") { type = NavType.LongType; defaultValue = 0L }),
            ) { StatsScreen(graph, nav, it.arguments?.getLong("chatId") ?: 0L) }
            composable(Routes.SETTINGS) { SettingsScreen(graph, nav) }
            composable(Routes.HISTORY) { HistoryScreen(graph, nav) }
            composable(
                "posts/{chatId}",
                arguments = listOf(navArgument("chatId") { type = NavType.LongType }),
            ) { PostsScreen(graph, nav, it.arguments!!.getLong("chatId")) }
            composable(
                "post/{chatId}/{postId}",
                arguments = listOf(
                    navArgument("chatId") { type = NavType.LongType },
                    navArgument("postId") { type = NavType.LongType },
                ),
            ) { PostDetailScreen(graph, nav, it.arguments!!.getLong("chatId"), it.arguments!!.getLong("postId")) }
            composable(
                "user/{userId}",
                arguments = listOf(navArgument("userId") { type = NavType.LongType }),
            ) { UserScreen(graph, nav, it.arguments!!.getLong("userId")) }
            composable(
                "analysis/{chatId}/{postId}",
                arguments = listOf(
                    navArgument("chatId") { type = NavType.LongType },
                    navArgument("postId") { type = NavType.LongType },
                ),
            ) { AnalysisScreen(graph, nav, it.arguments!!.getLong("chatId"), it.arguments!!.getLong("postId")) }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
