package dev.tgscope

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.tgscope.core.database.TgDatabase
import dev.tgscope.core.settings.SettingsRepository
import dev.tgscope.core.tdlib.ApiCredentials
import dev.tgscope.core.tdlib.TelegramClient
import dev.tgscope.domain.analytics.ContentAnalytics
import dev.tgscope.domain.export.Exporter
import dev.tgscope.domain.llm.LlmAnalyzer
import dev.tgscope.domain.stats.StatsRepository
import dev.tgscope.domain.sync.SyncEngine
import dev.tgscope.domain.sync.WatchCoordinator
import dev.tgscope.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class TGScopeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        createNotificationChannels()
        graph.appScope.launch {
            graph.db.syncRuns().cancelDangling(System.currentTimeMillis())
            graph.settings.ensureSalt()
        }
        graph.startTelegram()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, "Watch mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while TGScope follows channels in real time"
            },
        )
    }

    companion object {
        const val CHANNEL_WATCH = "watch"
    }
}

/** Hand-wired dependency graph: one instance per process. */
class AppGraph(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val db: TgDatabase = TgDatabase.create(app)
    val telegram = TelegramClient(
        databaseDir = File(app.filesDir, "tdlib"),
        filesDir = File(app.filesDir, "tdlib_files"),
        appVersion = appVersion(app),
    )
    val sync = SyncEngine(telegram, db, settings)
    val watch = WatchCoordinator(telegram, db, sync)
    val exporter = Exporter(db, settings)
    val analyzer = LlmAnalyzer(db, settings)
    val stats = StatsRepository(db)
    val content = ContentAnalytics(db)
    val scheduler = SyncScheduler(app)

    fun startTelegram() {
        appScope.launch {
            val s = settings.current()
            val creds = if (s.hasApiCredentials) {
                ApiCredentials(apiId = s.apiId!!, apiHash = s.apiHash!!, databaseKey = settings.databaseKey())
            } else {
                null
            }
            telegram.start(creds)
        }
    }

    /** Forgets API keys and wipes the TDLib session so the user can start over. */
    fun resetCredentials() {
        appScope.launch {
            settings.clearApiCredentials()
            runCatching { telegram.destroy(null) }
            telegram.start(null)
        }
    }

    private companion object {
        fun appVersion(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1"
    }
}

val Context.graph: AppGraph get() = (applicationContext as TGScopeApp).graph
