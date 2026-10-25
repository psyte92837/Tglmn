package dev.tgscope.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.tgscope.domain.sync.SyncMode
import dev.tgscope.domain.sync.SyncOutcome
import dev.tgscope.graph
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Scheduler mode: WorkManager periodic jobs (Android minimum is 15 minutes). */
class SyncScheduler(private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun syncNow(chatId: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_CHAT to chatId, SyncWorker.KEY_MODE to SyncMode.MANUAL.id))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork("sync-now-$chatId", ExistingWorkPolicy.KEEP, request)
    }

    fun schedule(chatId: Long, intervalMinutes: Int) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_CHAT to chatId, SyncWorker.KEY_MODE to SyncMode.SCHEDULER.id))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork("sync-periodic-$chatId", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(chatId: Long) {
        workManager.cancelUniqueWork("sync-periodic-$chatId")
        workManager.cancelUniqueWork("sync-now-$chatId")
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val chatId = inputData.getLong(KEY_CHAT, 0L)
        if (chatId == 0L) return Result.failure()
        val mode = SyncMode.entries.firstOrNull { it.id == inputData.getString(KEY_MODE) } ?: SyncMode.SCHEDULER

        if (!graph.telegram.awaitReady(60_000)) {
            return if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_ERROR to "Telegram is not authorized"))
        }
        if (graph.db.channels().get(chatId) == null) return Result.failure()

        var attempt = 0
        while (true) {
            when (val outcome = graph.sync.sync(chatId, mode)) {
                is SyncOutcome.Success, SyncOutcome.Skipped -> return Result.success()
                is SyncOutcome.Failed -> return Result.failure(workDataOf(KEY_ERROR to outcome.error))
                is SyncOutcome.Partial -> {
                    // Short FloodWaits are waited out in place; long ones go back to WorkManager.
                    if (outcome.retryAfterSeconds > MAX_INLINE_WAIT_SECONDS || attempt >= 2) return Result.retry()
                    delay(outcome.retryAfterSeconds * 1000L + 1000L)
                    attempt++
                }
            }
        }
    }

    companion object {
        const val KEY_CHAT = "chat_id"
        const val KEY_MODE = "mode"
        const val KEY_ERROR = "error"
        private const val MAX_INLINE_WAIT_SECONDS = 120
    }
}
