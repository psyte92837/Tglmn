package dev.tgscope

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.tgscope.ui.AppRoot
import dev.tgscope.ui.common.TGScopeTheme
import dev.tgscope.watch.WatchService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Watch mode is restarted from the foreground only (background FGS starts are restricted).
        lifecycleScope.launch {
            if (graph.settings.current().watchEnabled) WatchService.start(this@MainActivity)
        }
        setContent {
            TGScopeTheme { AppRoot(graph) }
        }
    }
}
