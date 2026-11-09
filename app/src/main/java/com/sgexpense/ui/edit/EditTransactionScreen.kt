package com.sgexpense.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sgexpense.AppContainer
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.model.TxType
import com.sgexpense.ui.components.appViewModel
import com.sgexpense.util.DateParsing
import com.sgexpense.util.Money
import com.sgexpense.util.toLocalDateTime
import com.sgexpense.util.toMillis
import com.sgexpense.util.todaySg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

data class EditForm(
    val amount: String = "",
    val type: TxType = TxType.EXPENSE,
    val merchant: String = "",
    val categoryId: Long? = null,
    val method: PaymentMethod = PaymentMethod.PAYNOW,
    val date: LocalDate = todaySg(),
    val time: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val note: String = "",
    val account: String = "",
    val cashback: String = "",
)

class EditViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val form = MutableStateFlow(EditForm())
    val categories = c.repo.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var original: Transaction? = null
        private set
    val loaded = MutableStateFlow(id == 0L)

    init {
        if (id != 0L) viewModelScope.launch {
            val tx = c.repo.get(id)
            original = tx
            if (tx != null) {
                val dt = tx.occurredAt.toLocalDateTime()
                form.value = EditForm(
                    amount = Money.plain(tx.amountCents),
                    type = tx.type,
                    merchant = tx.merchant,
                    categoryId = tx.categoryId,
                    method = tx.paymentMethod,
                    date = dt.toLocalDate(),
                    time = dt.toLocalTime().withSecond(0).withNano(0),
                    note = tx.note,
                    account = tx.account.orEmpty(),
                    cashback = if (tx.cashbackCents > 0) Money.plain(tx.cashbackCents) else "",
                )
            }
            loaded.value = true
        }
    }

    fun update(f: (EditForm) -> EditForm) { form.value = f(form.value) }

    /** Suggests a category from merchant rules while typing (only if none chosen yet). */
    fun suggestCategory() = viewModelScope.launch {
        val f = form.value
        if (f.categoryId == null && f.merchant.isNotBlank()) {
            c.repo.categorize(f.merchant, f.type, f.method)?.let { cat -> update { it.copy(categoryId = cat) } }
        }
    }

    fun save(onDone: () -> Unit): String? {
        val f = form.value
        val cents = Money.parseCents(f.amount)
        if (cents == null || cents == 0L) return "Enter an amount"
        val base = original ?: Transaction(amountCents = 0, occurredAt = 0, source = Source.MANUAL)
        val tx = base.copy(
            amountCents = cents,
            type = f.type,
            merchant = f.merchant.trim(),
            categoryId = f.categoryId,
            paymentMethod = f.method,
            occurredAt = LocalDateTime.of(f.date, f.time).toMillis(),
            note = f.note.trim(),
            account = f.account.trim().ifBlank { null },
            cashbackCents = Money.parseCents(f.cashback) ?: 0,
        )
        viewModelScope.launch {
            c.repo.save(tx, original)
            onDone()
        }
        return null
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        if (id != 0L) c.repo.delete(id)
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditTransactionScreen(id: Long, onDone: () -> Unit) {
    val vm = appViewModel(key = "edit-$id") { EditViewModel(it, id) }
    val f by vm.form.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == 0L) "Add transaction" else "Edit transaction") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (id != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                },
            )
        },
    ) { pad ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TxType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = f.type == t,
                        onClick = {
                            vm.update {
                                val keepCat = categories.firstOrNull { c -> c.id == it.categoryId }?.isIncome == (t != TxType.EXPENSE)
                                it.copy(type = t, categoryId = if (keepCat) it.categoryId else null)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, TxType.entries.size),
                    ) { Text(t.label) }
                }
            }
            OutlinedTextField(
                value = f.amount,
                onValueChange = { v -> vm.update { it.copy(amount = v.filter { ch -> ch.isDigit() || ch == '.' }) }; error = null },
                label = { Text("Amount (S$)") },
                textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = f.merchant,
                onValueChange = { v -> vm.update { it.copy(merchant = v) } },
                label = { Text(if (f.type == TxType.INCOME) "From" else "Merchant / payee") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusLost { vm.suggestCategory() },
            )

            Text("Category", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.filter { it.isIncome == (f.type != TxType.EXPENSE) }.forEach { c ->
                    FilterChip(
                        selected = f.categoryId == c.id,
                        onClick = { vm.update { it.copy(categoryId = if (it.categoryId == c.id) null else c.id) } },
                        label = { Text("${c.emoji} ${c.name}") },
                    )
                }
            }

            Text("Paid with", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethod.entries.forEach { m ->
                    FilterChip(selected = f.method == m, onClick = { vm.update { it.copy(method = m) } }, label = { Text(m.label) })
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) { Text(f.date.format(DateParsing.displayDate)) }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) { Text(f.time.format(DateParsing.displayTime)) }
            }
            OutlinedTextField(
                value = f.account,
                onValueChange = { v -> vm.update { it.copy(account = v) } },
                label = { Text("Account / card (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (f.method == PaymentMethod.SHOPBACK_PAY || f.cashback.isNotEmpty()) {
                OutlinedTextField(
                    value = f.cashback,
                    onValueChange = { v -> vm.update { it.copy(cashback = v.filter { ch -> ch.isDigit() || ch == '.' }) } },
                    label = { Text("Cashback earned (S$)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = f.note,
                onValueChange = { v -> vm.update { it.copy(note = v) } },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
            )
            vm.original?.rawText?.let { raw ->
                Text("Captured text", style = MaterialTheme.typography.labelMedium)
                Text(raw, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = { error = vm.save(onDone) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save") }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = f.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        vm.update { it.copy(date = java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) }
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (showTime) {
        val state = rememberTimePickerState(initialHour = f.time.hour, initialMinute = f.time.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = { vm.update { it.copy(time = LocalTime.of(state.hour, state.minute)) }; showTime = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Runs [block] when the field loses focus (used for category suggestion). */
@Composable
private fun Modifier.onFocusLost(block: () -> Unit): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this.onFocusChanged { st ->
        if (focused && !st.isFocused) block()
        focused = st.isFocused
    }
}
