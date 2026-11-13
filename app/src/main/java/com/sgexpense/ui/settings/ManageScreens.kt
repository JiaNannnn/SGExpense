package com.sgexpense.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sgexpense.SgExpenseApp
import com.sgexpense.capture.CaptureProcessor
import com.sgexpense.capture.CaptureSettings
import com.sgexpense.data.model.Category
import com.sgexpense.data.repository.Defaults
import com.sgexpense.ui.components.CategoryDot
import com.sgexpense.util.DateParsing
import com.sgexpense.util.toLocalDateTime
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as SgExpenseApp).container
    val categories by container.repo.categories.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Category(name = "", emoji = "📦", color = Defaults.colorChoices.first()) }) {
                Icon(Icons.Filled.Add, "Add category")
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(categories, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text(if (c.isIncome) "Income" else "Spending") },
                    leadingContent = { CategoryDot(c) },
                    modifier = Modifier.clickable { editing = c },
                )
            }
        }
    }

    editing?.let { cat ->
        CategoryDialog(
            cat,
            onDismiss = { editing = null },
            onSave = { c ->
                scope.launch { if (c.id == 0L) container.repo.addCategory(c) else container.repo.updateCategory(c) }
                editing = null
            },
            onDelete = if (cat.id != 0L) ({ scope.launch { container.repo.deleteCategory(cat) }; editing = null }) else null,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryDialog(initial: Category, onDismiss: () -> Unit, onSave: (Category) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial.name) }
    var emoji by remember { mutableStateOf(initial.emoji) }
    var color by remember { mutableStateOf(initial.color) }
    var isIncome by remember { mutableStateOf(initial.isIncome) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "New category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Defaults.emojiChoices.forEach { e ->
                        Box(
                            Modifier.size(40.dp)
                                .background(if (e == emoji) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, CircleShape)
                                .clickable { emoji = e },
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Defaults.colorChoices.forEach { c ->
                        Box(
                            Modifier.size(32.dp).background(Color(c), CircleShape)
                                .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = c }
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text("Income category") },
                    trailingContent = { Switch(isIncome, { isIncome = it }) },
                )
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete category", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(name = name.trim(), emoji = emoji, color = color, isIncome = isIncome)) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${initial.name}”?") },
            text = { Text("Its transactions are kept but become uncategorised.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private data class AppEntry(val pkg: String, val label: String, val installed: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = (context.applicationContext as SgExpenseApp).container.captureSettings
    val watched by settings.watched.collectAsState()
    var query by remember { mutableStateOf("") }

    val apps = remember {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != context.packageName }
            .toMap()
        val known = CaptureSettings.KNOWN_APPS.map { (pkg, label) -> AppEntry(pkg, installed[pkg] ?: label, pkg in installed) }
        val others = installed.filterKeys { it !in CaptureSettings.KNOWN_APPS }.map { AppEntry(it.key, it.value, true) }.sortedBy { it.label.lowercase() }
        known to others
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watched apps") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Text(
                    "Only notifications from ticked apps are read. Add your other banks or wallets here (e.g. if PayNow alerts come from another bank's app).",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { Text("Payment apps", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(apps.first, key = { "k" + it.pkg }) { app -> AppRow(app, app.pkg in watched) { on -> settings.setWatched(if (on) watched + app.pkg else watched - app.pkg) } }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text("Search installed apps") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            val q = query.lowercase(Locale.ENGLISH)
            val filtered = apps.second.filter { q.isEmpty() || it.label.lowercase(Locale.ENGLISH).contains(q) || it.pkg.contains(q) }
            items(filtered, key = { "o" + it.pkg }) { app -> AppRow(app, app.pkg in watched) { on -> settings.setWatched(if (on) watched + app.pkg else watched - app.pkg) } }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(app.label) },
        supportingContent = { Text(app.pkg + if (!app.installed) " · not installed" else "", style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Checkbox(checked, onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureLogScreen(onBack: () -> Unit, onOpenTransaction: (Long) -> Unit) {
    val container = (LocalContext.current.applicationContext as SgExpenseApp).container
    val logs by container.db.captureLog().observeRecent().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Capture log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { scope.launch { container.db.captureLog().deleteAll() } }) { Text("Clear") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Text(
                    "Every notification from a watched app appears here. “Ignored” means it wasn't recognised as a payment — " +
                        "if it was one, long-press to copy the text and send it over so a parser can be added.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (logs.isEmpty()) item { Text("Nothing captured yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(logs, key = { it.id }) { log ->
                val (label, color) = when (log.outcome) {
                    CaptureProcessor.OUTCOME_PARSED -> "Saved" to MaterialTheme.colorScheme.primary
                    CaptureProcessor.OUTCOME_MERGED -> "Merged (duplicate alert)" to MaterialTheme.colorScheme.tertiary
                    CaptureProcessor.OUTCOME_CASHBACK -> "Cashback" to MaterialTheme.colorScheme.tertiary
                    CaptureProcessor.OUTCOME_NEEDS_AMOUNT -> "Waiting for amount" to MaterialTheme.colorScheme.secondary
                    CaptureProcessor.OUTCOME_COMPLETED -> "Saved (amount typed)" to MaterialTheme.colorScheme.primary
                    CaptureProcessor.OUTCOME_DISMISSED -> "Not a payment" to MaterialTheme.colorScheme.onSurfaceVariant
                    CaptureProcessor.OUTCOME_DUPLICATE_PROMPT -> "Duplicate alert" to MaterialTheme.colorScheme.onSurfaceVariant
                    CaptureProcessor.OUTCOME_COVERED -> "Already captured by another app" to MaterialTheme.colorScheme.tertiary
                    else -> "Ignored" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                val appName = CaptureSettings.KNOWN_APPS[log.packageName] ?: log.packageName
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (log.transactionId != null) Modifier.clickable { onOpenTransaction(log.transactionId) } else Modifier)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text("$label · $appName · ${log.postedAt.toLocalDateTime().format(DateParsing.displayDay)} ${log.postedAt.toLocalDateTime().format(DateParsing.displayTime)}",
                            style = MaterialTheme.typography.labelMedium, color = color)
                        if (log.title.isNotBlank()) Text(log.title, style = MaterialTheme.typography.bodyMedium)
                        Text(log.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
