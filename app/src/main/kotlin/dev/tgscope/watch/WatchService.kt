package dev.tgscope.watch

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.tgscope.MainActivity
import dev.tgscope.TGScopeApp
import dev.tgscope.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Watch mode: a foreground service that keeps channels and their discussion groups opened in
 * TDLib and applies live updates. Android 15 caps dataSync services at ~6h per day; when the
 * system calls [onTimeout] we stop and the periodic scheduler keeps data fresh.
 */
class WatchService : LifecycleService() {
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job?.isActive != true) {
            job = lifecycleScope.launch {
                val graph = applicationContext.graph
                if (graph.telegram.awaitReady(120_000)) {
                    graph.watch.run()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, TGScopeApp.CHANNEL_WATCH)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("TGScope: Watch")
            .setContentText("Следим за каналами в реальном времени")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "dev.tgscope.watch.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
