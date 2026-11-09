package com.sgexpense.ui.list

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sgexpense.AppContainer
import com.sgexpense.data.model.Category
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.ui.components.MonthSwitcher
import com.sgexpense.ui.components.TransactionRow
import com.sgexpense.ui.components.appViewModel
import com.sgexpense.ui.components.spendCents
import com.sgexpense.util.DateParsing
import com.sgexpense.util.Money
import com.sgexpense.util.endMillis
import com.sgexpense.util.startMillis
import com.sgexpense.util.toLocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

data class ListFilter(
    val month: YearMonth = YearMonth.now(),
    val query: String = "",
    val categoryId: Long? = null,
    val method: PaymentMethod? = null,
    val source: Source? = null,
)

data class ListState(
    val filter: ListFilter = ListFilter(),
    val days: List<Pair<LocalDate, List<Transaction>>> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
    val total: Long = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModel(c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(ListFilter())
    private val monthTx = filter.flatMapLatest { f -> c.repo.observeRange(f.month.startMillis(), f.month.endMillis()) }

    val state = combine(filter, monthTx, c.repo.categories) { f, txs, cats ->
        val q = f.query.trim().lowercase(Locale.ENGLISH)
        val filtered = txs.filter { t ->
            (f.categoryId == null || t.categoryId == f.categoryId) &&
                (f.method == null || t.paymentMethod == f.method) &&
                (f.source == null || t.source == f.source) &&
                (q.isEmpty() || t.merchant.lowercase(Locale.ENGLISH).contains(q) || t.note.lowercase(Locale.ENGLISH).contains(q) ||
                    Money.plain(t.amountCents).contains(q))
        }
        ListState(
            filter = f,
            days = filtered.groupBy { it.occurredAt.toLocalDate() }.toList().sortedByDescending { it.first },
            categories = cats.associateBy { it.id },
            total = spendCents(filtered),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListState())

    fun update(f: (ListFilter) -> ListFilter) { filter.value = f(filter.value) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(onEdit: (Long) -> Unit) {
    val vm = appViewModel { ListViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val f = s.filter

    Scaffold(topBar = { TopAppBar(title = { Text("Transactions") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            MonthSwitcher(f.month, { m -> vm.update { it.copy(month = m) } })
            OutlinedTextField(
                value = f.query,
                onValueChange = { q -> vm.update { it.copy(query = q) } },
                placeholder = { Text("Search merchant, note or amount") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterDropdown(
                    label = f.categoryId?.let { id -> s.categories[id]?.let { "${it.emoji} ${it.name}" } } ?: "Category",
                    selected = f.categoryId != null,
                    options = listOf<Pair<String, Long?>>("All categories" to null) + s.categories.values.map { "${it.emoji} ${it.name}" to it.id },
                    onSelect = { id -> vm.update { it.copy(categoryId = id) } },
                )
                FilterDropdown(
                    label = f.method?.label ?: "Payment",
                    selected = f.method != null,
                    options = listOf<Pair<String, PaymentMethod?>>("All methods" to null) + PaymentMethod.entries.map { it.label to it },
                    onSelect = { m -> vm.update { it.copy(method = m) } },
                )
                FilterDropdown(
                    label = f.source?.label ?: "Source",
                    selected = f.source != null,
                    options = listOf<Pair<String, Source?>>("All sources" to null) + Source.entries.map { it.label to it },
                    onSelect = { src -> vm.update { it.copy(source = src) } },
                )
            }
            Text(
                "Spent: ${Money.format(s.total)}",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (s.days.isEmpty()) {
                    item { Text("Nothing here for this month.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                s.days.forEach { (day, txs) ->
                    item(key = "h$day") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(day.format(DateParsing.displayDay), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(Money.format(spendCents(txs)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(txs, key = { it.id }) { tx -> TransactionRow(tx, s.categories[tx.categoryId], onClick = { onEdit(tx.id) }) }
                }
            }
        }
    }
}

@Composable
private fun <T> FilterDropdown(label: String, selected: Boolean, options: List<Pair<String, T>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
            }
        }
    }
}
