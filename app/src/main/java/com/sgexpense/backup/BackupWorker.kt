package com.sgexpense.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sgexpense.SgExpenseApp
import java.util.concurrent.TimeUnit

/** Weekly: overwrite the chosen backup file (e.g. on Google Drive) with a fresh JSON backup. */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val backup = (applicationContext as SgExpenseApp).container.backupManager
        val uri = backup.autoBackupUri ?: return Result.success()
        return try {
            backup.writeBackup(uri)
            Result.success()
        } catch (e: Exception) {
            backup.lastBackupError = e.message ?: e.javaClass.simpleName
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

object BackupScheduler {
    private const val NAME = "weekly-backup"

    fun ensureScheduled(context: Context) {
        val req = PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
