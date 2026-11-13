package com.sgexpense.ui.quickadd

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sgexpense.SgExpenseApp
import com.sgexpense.capture.AmountPromptNotifier
import com.sgexpense.capture.AmountPromptService
import com.sgexpense.data.model.TxType
import com.sgexpense.ui.theme.SgExpenseTheme
import com.sgexpense.util.Money
import kotlinx.coroutines.launch

/**
 * Small popup for a UOB alert that came without an amount. Opened from the prompt notification
 * or from the "Needs amount" list on Home. "Later" keeps it pending.
 */
class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logId = intent.getLongExtra(AmountPromptNotifier.EXTRA_LOG_ID, -1)
        val service = (application as SgExpenseApp).container.amountPrompts
        setContent {
            SgExpenseTheme {
                QuickAddDialog(logId, service, onClose = { finish() })
            }
        }
    }

    companion object {
        fun intent(context: Context, logId: Long): Intent =
            Intent(context, QuickAddActivity::class.java)
                .putExtra(AmountPromptNotifier.EXTRA_LOG_ID, logId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                // A distinct data URI keeps one PendingIntent per prompt.
                .setData(android.net.Uri.parse("sgexpense://prompt/$logId"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAddDialog(logId: Long, service: AmountPromptService, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as SgExpenseApp).container
    val categories by container.repo.categories.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AmountPromptService.Pending?>(null) }
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TxType.EXPENSE) }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(logId) {
        pending = service.pending(logId)
        pending?.let { p ->
            merchant = p.hint.merchant
            type = p.hint.type
        }
        loaded = true
    }
    if (!loaded) return

    val p = pending
    if (p == null) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Already handled") },
            text = { Text("This alert has already been added or dismissed.") },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
        return
    }

    val save: () -> Unit = {
        val cents = Money.parseCents(amount)
        if (cents == null || cents == 0L) {
            error = "Enter the amount"
        } else {
            scope.launch {
                service.complete(logId, cents, merchant, type, categoryId)
                onClose()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(p.hint.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    AmountPromptNotifier.describe(p.hint, p.log.postedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' }; error = null },
                    label = { Text("Amount (S$)") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    // The keyboard can cover the dialog's buttons, so its ✓ key saves.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(if (type == TxType.EXPENSE) "Paid to" else "From") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(TxType.EXPENSE, TxType.INCOME).forEach { t ->
                        FilterChip(type == t, { type = t; categoryId = null }, label = { Text(t.label) })
                    }
                }
                Text("Category (optional)", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.filter { it.isIncome == (type != TxType.EXPENSE) }.forEach { c ->
                        FilterChip(
                            selected = categoryId == c.id,
                            onClick = { categoryId = if (categoryId == c.id) null else c.id },
                            label = { Text("${c.emoji} ${c.name}") },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = save) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { scope.launch { service.dismiss(logId); onClose() } }) { Text("Not a payment") }
                TextButton(onClick = onClose) { Text("Later") }
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
