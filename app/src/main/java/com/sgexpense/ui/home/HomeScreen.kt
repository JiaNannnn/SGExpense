package com.sgexpense.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import com.sgexpense.capture.AmountPromptNotifier
import com.sgexpense.capture.parsers.MissingAmountHint
import com.sgexpense.capture.parsers.UobMissingAmount
import com.sgexpense.ui.quickadd.QuickAddActivity
import com.sgexpense.util.DateParsing
import com.sgexpense.util.toLocalDateTime
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sgexpense.AppContainer
import com.sgexpense.data.model.Category
import com.sgexpense.data.model.Transaction
import com.sgexpense.ui.components.SectionHeader
import com.sgexpense.ui.components.TransactionRow
import com.sgexpense.ui.components.appViewModel
import com.sgexpense.ui.components.incomeCents
import com.sgexpense.ui.components.spendCents
import com.sgexpense.ui.theme.incomeColor
import com.sgexpense.util.Money
import com.sgexpense.util.endMillis
import com.sgexpense.util.startMillis
import com.sgexpense.util.toLocalDate
import com.sgexpense.util.todaySg
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

data class PendingPrompt(val logId: Long, val postedAt: Long, val hint: MissingAmountHint)

data class HomeState(
    val monthTx: List<Transaction> = emptyList(),
    val recent: List<Transaction> = emptyList(),
    val captured: List<Transaction> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val pending: List<PendingPrompt> = emptyList(),
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val month = YearMonth.now()
    private val pendingFlow = c.db.captureLog().observePending().map { logs ->
        logs.mapNotNull { log -> UobMissingAmount.hint(log.title, log.text)?.let { PendingPrompt(log.id, log.postedAt, it) } }
    }
    val state = combine(
        c.repo.observeRange(month.startMillis(), month.endMillis()),
        c.repo.observeRecent(15),
        c.repo.observeCapturedSince(System.currentTimeMillis() - 24 * 3600_000L),
        c.repo.categories,
        pendingFlow,
    ) { m, r, cap, cats, pend -> HomeState(m, r, cap, cats.associateBy { it.id }, pend) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())

    fun hasAccess() = c.captureSettings.hasNotificationAccess()

    /** Prompts are on, but Android won't let SG Expense show them. */
    fun promptsBlocked() = c.captureSettings.promptMissingAmount && !c.captureSettings.canPostNotifications()

    fun undo(tx: Transaction) = viewModelScope.launch { c.repo.delete(tx.id) }

    fun dismissPrompt(logId: Long) = viewModelScope.launch { c.amountPrompts.dismiss(logId) }
}

fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onAdd: () -> Unit, onEdit: (Long) -> Unit, onSetupCapture: () -> Unit, onSeeAll: () -> Unit) {
    val vm = appViewModel { HomeViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(true) }
    var promptsBlocked by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        hasAccess = vm.hasAccess()
        promptsBlocked = vm.promptsBlocked()
        onPauseOrDispose { }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        promptsBlocked = vm.promptsBlocked()
        if (!granted) context.startActivity(appNotificationSettings(context))
    }
    val today = todaySg()
    val todaySpend = spendCents(s.monthTx.filter { it.occurredAt.toLocalDate() == today })

    Scaffold(
        topBar = { TopAppBar(title = { Text("SG Expense") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") })
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Spent this month", style = MaterialTheme.typography.labelLarge)
                        Text(Money.format(spendCents(s.monthTx)), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Today", style = MaterialTheme.typography.labelMedium)
                                Text(Money.format(todaySpend), style = MaterialTheme.typography.titleMedium)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Income", style = MaterialTheme.typography.labelMedium)
                                Text(Money.format(incomeCents(s.monthTx)), style = MaterialTheme.typography.titleMedium, color = incomeColor())
                            }
                        }
                    }
                }
            }
            if (!hasAccess) {
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Turn on auto-capture", style = MaterialTheme.typography.titleSmall)
                                Text("Record GPay, UOB (card / PayNow), ShopBack, FairPrice Pay and SimplyGo payments from their notifications.", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = onSetupCapture) { Text("Set up") }
                        }
                    }
                }
            }
            if (hasAccess && promptsBlocked) {
                item {
                    Card(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Allow SG Expense notifications", style = MaterialTheme.typography.titleSmall)
                                Text("Needed to pop up and ask for the amount when a UOB PayNow alert doesn't show one.", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else context.startActivity(appNotificationSettings(context))
                            }) { Text("Allow") }
                        }
                    }
                }
            }
            if (s.pending.isNotEmpty()) {
                item { SectionHeader("Needs amount (${s.pending.size})") }
                items(s.pending, key = { "p${it.logId}" }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { context.startActivity(QuickAddActivity.intent(context, p.logId)) }
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.EditNote, null, tint = MaterialTheme.colorScheme.secondary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.hint.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                p.postedAt.toLocalDateTime().format(DateParsing.displayDay) + " · " +
                                    AmountPromptNotifier.describe(p.hint, p.postedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = { context.startActivity(QuickAddActivity.intent(context, p.logId)) }) { Text("Add") }
                        IconButton(onClick = { vm.dismissPrompt(p.logId) }) { Icon(Icons.Filled.Close, "Not a payment") }
                    }
                }
            }
            if (s.captured.isNotEmpty()) {
                item { SectionHeader("Recently captured") }
                items(s.captured, key = { "c${it.id}" }) { tx ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { TransactionRow(tx, s.categories[tx.categoryId], onClick = { onEdit(tx.id) }) }
                        TextButton(onClick = { vm.undo(tx) }) { Text("Undo") }
                    }
                }
            }
            item {
                SectionHeader("Recent") { TextButton(onClick = onSeeAll) { Text("See all") } }
            }
            if (s.recent.isEmpty()) {
                item {
                    Text(
                        "No expenses yet. Tap Add, import a receipt or statement, or turn on auto-capture.",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(s.recent, key = { it.id }) { tx ->
                TransactionRow(tx, s.categories[tx.categoryId], onClick = { onEdit(tx.id) }, showDate = true)
            }
        }
    }
}
