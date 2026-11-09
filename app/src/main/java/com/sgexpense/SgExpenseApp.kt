package com.sgexpense

import android.app.Application
import android.content.Context
import com.sgexpense.backup.BackupManager
import com.sgexpense.backup.BackupScheduler
import com.sgexpense.capture.CaptureProcessor
import com.sgexpense.capture.CaptureSettings
import com.sgexpense.data.db.AppDatabase
import com.sgexpense.data.repository.ExpenseRepository
import com.sgexpense.importer.ImportService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val db: AppDatabase = AppDatabase.build(appContext)
    val repo = ExpenseRepository(db)
    val captureSettings = CaptureSettings(appContext)
    val captureProcessor = CaptureProcessor(db, repo)
    val importService = ImportService(appContext, repo)
    val backupManager = BackupManager(appContext, db)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class SgExpenseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.repo.seedIfEmpty() }
        BackupScheduler.ensureScheduled(this)
    }
}
