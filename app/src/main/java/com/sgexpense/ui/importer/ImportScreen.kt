package com.sgexpense.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sgexpense.AppContainer
import com.sgexpense.data.model.Category
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.TxType
import com.sgexpense.importer.FileResult
import com.sgexpense.importer.ReviewItem
import com.sgexpense.importer.sheet.ColumnMapping
import com.sgexpense.importer.sheet.SheetData
import com.sgexpense.ui.components.CategoryDot
import com.sgexpense.ui.theme.incomeColor
import com.sgexpense.util.DateParsing
import com.sgexpense.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportState(
    val items: List<ReviewItem> = emptyList(),
    val busy: String? = null,
    val message: String? = null,
    val pendingSheet: SheetData? = null,
    val pendingName: String = "",
)

class ImportViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(ImportState())
    val categories = c.repo.categories.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var nextKey = 0

    private fun set(f: (ImportState) -> ImportState) { state.value = f(state.value) }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var found = 0
            var failed = 0
            uris.forEachIndexed { i, uri ->
                set { it.copy(busy = "Reading image ${i + 1} of ${uris.size}…") }
                try {
                    val drafts = c.importService.analyzeImage(uri)
                    val items = c.importService.toReviewItems(drafts, "Image ${i + 1}", Source.OCR, nextKey)
                    nextKey += items.size
                    found += items.size
                    set { it.copy(items = it.items + items) }
                } catch (e: Exception) {
                    failed++
                }
            }
            set {
                it.copy(busy = null, message = buildString {
                    append("Found $found transaction(s) in ${uris.size} image(s).")
                    if (failed > 0) append(" $failed image(s) could not be read.")
                    if (found == 0) append(" Try a clearer photo, or add it manually.")
                })
            }
        }
    }

    fun addFile(uri: Uri, name: String) {
        viewModelScope.launch {
            set { it.copy(busy = "Reading $name…") }
            try {
                val sheet = withContext(Dispatchers.IO) { c.importService.readSheet(uri) }
                when (val r = c.importService.analyzeSheet(sheet)) {
                    is FileResult.Drafts -> addDrafts(r.drafts, name)
                    is FileResult.NeedsMapping -> set { it.copy(busy = null, pendingSheet = r.sheet, pendingName = name) }
                }
            } catch (e: Exception) {
                set { it.copy(busy = null, message = "Could not read $name: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun applyMapping(mapping: ColumnMapping) {
        val sheet = state.value.pendingSheet ?: return
        val name = state.value.pendingName
        set { it.copy(pendingSheet = null) }
        viewModelScope.launch { addDrafts(c.importService.mapWith(sheet, mapping), name) }
    }

    fun cancelMapping() = set { it.copy(pendingSheet = null) }

    private suspend fun addDrafts(drafts: List<com.sgexpense.importer.DraftTransaction>, name: String) {
        val items = c.importService.toReviewItems(drafts, name, Source.IMPORT, nextKey)
        nextKey += items.size
        val dups = items.count { it.duplicate }
        set {
            it.copy(
                items = it.items + items, busy = null,
                message = "Found ${items.size} row(s) in $name." + if (dups > 0) " $dups look like duplicates and are unticked." else "",
            )
        }
    }

    fun toggle(key: Int) = set { s -> s.copy(items = s.items.map { if (it.key == key) it.copy(selected = !it.selected) else it }) }
    fun setAll(v: Boolean) = set { s -> s.copy(items = s.items.map { it.copy(selected = v) }) }
    fun replace(item: ReviewItem) = set { s -> s.copy(items = s.items.map { if (it.key == item.key) item else it }) }
    fun clear() = set { ImportState() }
    fun dismissMessage() = set { it.copy(message = null) }

    fun commit() = viewModelScope.launch {
        val n = c.importService.commit(state.value.items)
        set { ImportState(message = "Imported $n transaction(s).") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: ImportViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val catMap = remember(categories) { categories.associateBy { it.id } }
    val context = androidx.compose.ui.platform.LocalContext.current
    var editing by remember { mutableStateOf<ReviewItem?>(null) }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris -> vm.addImages(uris) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addFile(uri, displayName(context, uri))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                actions = { if (s.items.isNotEmpty()) TextButton(onClick = vm::clear) { Text("Clear") } },
            )
        },
        bottomBar = {
            val n = s.items.count { it.selected }
            if (s.items.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$n of ${s.items.size} selected · ${Money.format(s.items.filter { it.selected }.sumOf { it.draft.amountCents })}", Modifier.weight(1f))
                    Button(onClick = { vm.commit() }, enabled = n > 0) { Text("Import $n") }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElevatedCard(
                        onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(16.dp).heightIn(min = 96.dp)) {
                            Icon(Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Images", style = MaterialTheme.typography.titleMedium)
                            Text("Receipts, PayNow / GPay / ShopBack / UOB screenshots", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    ElevatedCard(
                        onClick = {
                            pickFile.launch(arrayOf(
                                "text/csv", "text/comma-separated-values", "text/plain", "text/html",
                                "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/octet-stream",
                            ))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(16.dp).heightIn(min = 96.dp)) {
                            Icon(Icons.Filled.TableChart, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Excel / CSV", style = MaterialTheme.typography.titleMedium)
                            Text("UOB statement export (.xls, .xlsx, .csv)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            s.busy?.let { msg ->
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
            s.message?.let { msg ->
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::dismissMessage) { Text("OK") }
                    }
                }
            }
            if (s.items.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Review — tap a row to fix it", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { vm.setAll(true) }) { Text("All") }
                        TextButton(onClick = { vm.setAll(false) }) { Text("None") }
                    }
                }
            } else if (s.busy == null) {
                item {
                    Text(
                        "Tip: you can also share photos or statement files to SG Expense from Gallery, Files or Google Drive.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(s.items, key = { it.key }) { item ->
                ReviewRow(item, catMap[item.categoryId], onToggle = { vm.toggle(item.key) }, onClick = { editing = item })
            }
        }
    }

    editing?.let { item ->
        EditDraftDialog(item, categories, onDismiss = { editing = null }, onSave = { vm.replace(it); editing = null })
    }
    s.pendingSheet?.let { sheet ->
        ColumnMappingDialog(sheet, s.pendingName, onCancel = vm::cancelMapping, onApply = vm::applyMapping)
    }
}

@Composable
private fun ReviewRow(item: ReviewItem, category: Category?, onToggle: () -> Unit, onClick: () -> Unit) {
    val d = item.draft
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.selected, onCheckedChange = { onToggle() })
        CategoryDot(category, 34)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(d.merchant.ifBlank { "(no description)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${d.date.format(DateParsing.displayDate)} · ${d.paymentMethod.label} · ${item.sourceLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.duplicate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Possible duplicate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(
            (if (d.type == TxType.EXPENSE) "-" else "+") + Money.format(d.amountCents),
            fontWeight = FontWeight.SemiBold,
            color = if (d.type == TxType.EXPENSE) MaterialTheme.colorScheme.onSurface else incomeColor(),
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDraftDialog(item: ReviewItem, categories: List<Category>, onDismiss: () -> Unit, onSave: (ReviewItem) -> Unit) {
    var amount by remember { mutableStateOf(Money.plain(item.draft.amountCents)) }
    var merchant by remember { mutableStateOf(item.draft.merchant) }
    var dateText by remember { mutableStateOf(item.draft.date.toString()) }
    var type by remember { mutableStateOf(item.draft.type) }
    var method by remember { mutableStateOf(item.draft.paymentMethod) }
    var categoryId by remember { mutableStateOf(item.categoryId) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit row") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount (S$)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant / description") }, singleLine = true)
                OutlinedTextField(dateText, { dateText = it }, label = { Text("Date (yyyy-mm-dd or dd/mm/yyyy)") }, singleLine = true,
                    isError = error != null, supportingText = error?.let { { Text(it) } })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TxType.entries.forEach { t -> FilterChip(type == t, { type = t }, label = { Text(t.label) }) }
                }
                Text("Paid with", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaymentMethod.entries.forEach { m -> FilterChip(method == m, { method = m }, label = { Text(m.label) }) }
                }
                Text("Category", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.filter { it.isIncome == (type != TxType.EXPENSE) }.forEach { c ->
                        FilterChip(categoryId == c.id, { categoryId = c.id }, label = { Text("${c.emoji} ${c.name}") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = Money.parseCents(amount)
                val date = DateParsing.findDate(dateText)
                when {
                    cents == null || cents == 0L -> error = "Check the amount"
                    date == null -> error = "Check the date"
                    else -> onSave(item.copy(
                        draft = item.draft.copy(amountCents = cents, merchant = merchant.trim(), date = date, type = type, paymentMethod = method),
                        categoryId = categoryId,
                        selected = true,
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lets the user pick header row + columns when a file's layout isn't recognised. */
@Composable
private fun ColumnMappingDialog(sheet: SheetData, name: String, onCancel: () -> Unit, onApply: (ColumnMapping) -> Unit) {
    var headerRow by remember { mutableIntStateOf(sheet.rows.indexOfFirst { r -> r.count { it.isNotBlank() } >= 3 }.coerceAtLeast(0)) }
    var date by remember { mutableIntStateOf(-1) }
    var desc by remember { mutableIntStateOf(-1) }
    var amount by remember { mutableIntStateOf(-1) }
    var withdrawal by remember { mutableIntStateOf(-1) }
    var deposit by remember { mutableIntStateOf(-1) }
    var positiveIsExpense by remember { mutableStateOf(true) }
    val headers = sheet.rows.getOrNull(headerRow).orEmpty().mapIndexed { i, h -> h.ifBlank { "Column ${i + 1}" } }
    val sample = sheet.rows.getOrNull(headerRow + 1).orEmpty()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Match columns") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$name wasn't recognised. Choose which columns to use.", style = MaterialTheme.typography.bodySmall)
                ColumnPicker("Header row", (0 until minOf(sheet.rows.size, 40)).map { r -> "Row ${r + 1}: " + sheet.rows[r].filter { it.isNotBlank() }.take(3).joinToString(" | ") }, headerRow) {
                    headerRow = it; date = -1; desc = -1; amount = -1; withdrawal = -1; deposit = -1
                }
                val opts = headers.mapIndexed { i, h -> h + (sample.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { "  (e.g. ${it.take(20)})" } ?: "") }
                ColumnPicker("Date", opts, date) { date = it }
                ColumnPicker("Description", opts, desc) { desc = it }
                ColumnPicker("Amount (single column)", opts, amount) { amount = it }
                ColumnPicker("…or Withdrawal / Debit", opts, withdrawal) { withdrawal = it }
                ColumnPicker("…and Deposit / Credit", opts, deposit) { deposit = it }
                if (amount >= 0 && withdrawal < 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Positive amounts are spending", Modifier.weight(1f))
                        Switch(positiveIsExpense, { positiveIsExpense = it })
                    }
                }
            }
        },
        confirmButton = {
            val m = ColumnMapping(headerRow, date, desc, amount, withdrawal, deposit, positiveIsExpense)
            TextButton(onClick = { onApply(m) }, enabled = m.isValid) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ColumnPicker(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.getOrNull(selected) ?: "— none —", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("— none —") }, onClick = { onSelect(-1); open = false })
                options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o, maxLines = 1) }, onClick = { onSelect(i); open = false }) }
            }
        }
    }
}

fun displayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "file"
