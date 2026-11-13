package com.sgexpense.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sgexpense.SgExpenseApp
import com.sgexpense.ui.components.SectionHeader
import com.sgexpense.ui.theme.incomeColor
import com.sgexpense.util.DateParsing
import com.sgexpense.util.endMillis
import com.sgexpense.util.startMillis
import com.sgexpense.util.toLocalDateTime
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onCategories: () -> Unit, onWatchedApps: () -> Unit, onCaptureLog: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as SgExpenseApp).container
    val capture = container.captureSettings
    val backup = container.backupManager
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var hasAccess by remember { mutableStateOf(capture.hasNotificationAccess()) }
    var batteryOk by remember { mutableStateOf(true) }
    var enabled by remember { mutableStateOf(capture.enabled) }
    var promptsOn by remember { mutableStateOf(capture.promptMissingAmount) }
    var canNotify by remember { mutableStateOf(capture.canPostNotifications()) }
    var autoUri by remember { mutableStateOf(backup.autoBackupUri) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var exportRange by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    LifecycleResumeEffect(Unit) {
        hasAccess = capture.hasNotificationAccess()
        canNotify = capture.canPostNotifications()
        batteryOk = (context.getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(context.packageName)
        refresh++
        onPauseOrDispose { }
    }

    fun run(label: String, block: suspend () -> String) = scope.launch {
        val msg = try { block() } catch (e: Exception) { "$label failed: ${e.message ?: e.javaClass.simpleName}" }
        snackbar.showSnackbar(msg)
    }

    val stamp = { LocalDateTime.now().format(DateParsing.fileStamp) }
    val backupNow = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run("Backup") { backup.writeBackup(uri); refresh++; "Backup saved" }
    }
    val setAutoTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run("Auto-backup") {
            backup.setAutoBackupTarget(uri); autoUri = uri
            backup.writeBackup(uri); refresh++
            "Weekly backups will overwrite this file"
        }
    }
    val restorePick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingRestore = uri }
    val csvTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val r = exportRange
        if (uri != null && r != null) run("Export") { "Exported ${backup.exportCsv(uri, r.first, r.second)} rows" }
    }
    val xlsxTarget = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        val r = exportRange
        if (uri != null && r != null) run("Export") { "Exported ${backup.exportXlsx(uri, r.first, r.second)} rows" }
    }
    var exportKind by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            SectionHeader("Auto-capture")
            SettingRow(
                if (hasAccess) "Notification access granted" else "Grant notification access",
                if (hasAccess) "Payments from watched apps are recorded automatically"
                else "Needed to read GPay, UOB (PayNow / card), ShopBack, FairPrice and SimplyGo notifications",
                if (hasAccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
            ) { context.startActivity(capture.notificationAccessIntent()) }
            ListItem(
                headlineContent = { Text("Capture payments") },
                supportingContent = { Text("Pause without revoking access") },
                trailingContent = { Switch(enabled, { enabled = it; capture.enabled = it }) },
            )
            ListItem(
                headlineContent = { Text("Ask for missing amounts") },
                supportingContent = {
                    Text(
                        if (promptsOn && !canNotify) "Turned on, but SG Expense notifications are blocked — tap to allow"
                        else "When a UOB alert (e.g. PayNow) has no amount, pop up a notification so you can type it"
                    )
                },
                trailingContent = { Switch(promptsOn, { promptsOn = it; capture.promptMissingAmount = it }) },
                modifier = Modifier.clickable(enabled = promptsOn && !canNotify) {
                    context.startActivity(com.sgexpense.ui.home.appNotificationSettings(context))
                },
            )
            SettingRow("Watched apps", "${capture.watched.value.size} apps", Icons.Filled.Apps, onWatchedApps)
            SettingRow("Capture log", "Raw notifications from the last 30 days", Icons.AutoMirrored.Filled.ReceiptLong, onCaptureLog)
            if (!batteryOk) {
                SettingRow(
                    "Allow background activity",
                    "Some phones stop the capture service to save battery. Set SG Expense to “Unrestricted”.",
                    Icons.Filled.BatteryAlert,
                ) { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionHeader("Categories")
            SettingRow("Manage categories", "Add, rename, recolour", Icons.Filled.Category, onCategories)

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionHeader("Backup (Google Drive)")
            ListItem(
                headlineContent = { Text("Android backup") },
                supportingContent = {
                    Text("Your data is included in your phone's Google backup automatically. Check that “Backup by Google One” is on in system Settings › Google › Backup.")
                },
                leadingContent = { Icon(Icons.Filled.CloudUpload, null) },
            )
            val last = remember(refresh) { backup.lastBackupAt }
            val lastErr = remember(refresh) { backup.lastBackupError }
            SettingRow("Back up now", "Save a backup file — pick Google Drive in the file picker", Icons.Filled.Backup) {
                backupNow.launch("sgexpense-backup-${stamp()}.json")
            }
            SettingRow(
                if (autoUri == null) "Set up weekly backup file" else "Weekly backup on",
                when {
                    lastErr != null -> "Last attempt failed: $lastErr"
                    autoUri != null && last > 0 -> "Last backup: ${last.toLocalDateTime().format(DateParsing.displayDate)}"
                    autoUri != null -> "Waiting for first run"
                    else -> "Choose a file on Google Drive; it's overwritten every week"
                },
                Icons.Filled.Schedule,
            ) { setAutoTarget.launch("sgexpense-backup.json") }
            if (autoUri != null) {
                TextButton(onClick = { backup.setAutoBackupTarget(null); autoUri = null }, modifier = Modifier.padding(start = 56.dp)) {
                    Text("Turn off weekly backup")
                }
            }
            SettingRow("Restore from backup", "Replaces all current data", Icons.Filled.Restore) { restorePick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            SectionHeader("Export")
            SettingRow("Export to Excel (.xlsx)", "Choose a month or everything", Icons.Filled.GridOn) { exportKind = "xlsx" }
            SettingRow("Export to CSV", "Opens in Excel / Google Sheets", Icons.Filled.Description) { exportKind = "csv" }
        }
    }

    exportKind?.let { kind ->
        val now = YearMonth.now()
        AlertDialog(
            onDismissRequest = { exportKind = null },
            title = { Text("Export which period?") },
            text = {
                Column {
                    listOf(
                        "This month (${now.format(DateParsing.displayMonth)})" to (now.startMillis() to now.endMillis()),
                        "Last month (${now.minusMonths(1).format(DateParsing.displayMonth)})" to (now.minusMonths(1).startMillis() to now.minusMonths(1).endMillis()),
                        "This year" to (now.withMonth(1).startMillis() to now.withMonth(12).endMillis()),
                        "Everything" to (0L to Long.MAX_VALUE),
                    ).forEach { (label, range) ->
                        TextButton(onClick = {
                            exportRange = range
                            exportKind = null
                            val file = "sgexpense-${stamp()}.$kind"
                            if (kind == "csv") csvTarget.launch(file) else xlsxTarget.launch(file)
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exportKind = null }) { Text("Cancel") } },
        )
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore backup?") },
            text = { Text("All current transactions and categories will be replaced by the backup's contents. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    run("Restore") { "Restored ${backup.restore(uri)} transactions" }
                }) { Text("Replace my data") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, null, tint = if (icon == Icons.Filled.CheckCircle) incomeColor() else if (icon == Icons.Filled.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
