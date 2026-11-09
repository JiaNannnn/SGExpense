package com.sgexpense.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sgexpense.AppContainer
import com.sgexpense.data.model.Category
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.TxType
import com.sgexpense.ui.components.MonthSwitcher
import com.sgexpense.ui.components.appViewModel
import com.sgexpense.ui.components.incomeCents
import com.sgexpense.ui.components.spendCents
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
import java.time.YearMonth

data class Slice(val label: String, val emoji: String, val color: Long, val cents: Long)

data class StatsState(
    val month: YearMonth = YearMonth.now(),
    val spend: Long = 0,
    val income: Long = 0,
    val cashback: Long = 0,
    val byCategory: List<Slice> = emptyList(),
    val byMethod: List<Pair<PaymentMethod, Long>> = emptyList(),
    val daily: List<Long> = emptyList(),
    val transitCents: Long = 0,
    val transitDays: Int = 0,
    val transitCharges: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(c: AppContainer) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now())
    private val txs = month.flatMapLatest { m -> c.repo.observeRange(m.startMillis(), m.endMillis()) }

    val state = combine(month, txs, c.repo.categories) { m, list, cats ->
        val catMap: Map<Long, Category> = cats.associateBy { it.id }
        val spending = list.filter { it.type != TxType.INCOME }
        val signed = { t: com.sgexpense.data.model.Transaction -> t.amountCents * t.type.spendSign }
        val byCat = spending.groupBy { it.categoryId }.map { (id, ts) ->
            val c = id?.let { catMap[it] }
            Slice(c?.name ?: "Uncategorised", c?.emoji ?: "❔", c?.color ?: 0xFF9E9E9E, ts.sumOf(signed))
        }.filter { it.cents > 0 }.sortedByDescending { it.cents }
        val byMethod = spending.groupBy { it.paymentMethod }.map { (k, ts) -> k to ts.sumOf(signed) }
            .filter { it.second > 0 }.sortedByDescending { it.second }
        val daily = LongArray(m.lengthOfMonth())
        spending.forEach { t -> daily[t.occurredAt.toLocalDate().dayOfMonth - 1] += signed(t) }
        val transit = spending.filter { it.paymentMethod == PaymentMethod.SIMPLYGO }
        StatsState(
            m, spendCents(list), incomeCents(list), list.sumOf { it.cashbackCents }, byCat, byMethod, daily.toList(),
            transitCents = transit.sumOf(signed),
            transitDays = transit.map { it.occurredAt.toLocalDate() }.distinct().size,
            transitCharges = transit.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen() {
    val vm = appViewModel { StatsViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Stats") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            MonthSwitcher(s.month, { vm.month.value = it })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Spent", Money.format(s.spend), Modifier.weight(1f))
                StatTile("Income", Money.format(s.income), Modifier.weight(1f))
                StatTile("Cashback", Money.format(s.cashback), Modifier.weight(1f))
            }

            if (s.transitCharges > 0) {
                Card(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🚇", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Public transport (SimplyGo)", style = MaterialTheme.typography.titleSmall)
                            val avg = if (s.transitDays > 0) s.transitCents / s.transitDays else 0
                            Text(
                                "${s.transitDays} travel day(s) · avg ${Money.format(avg)} per day",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Money.format(s.transitCents), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("By category", style = MaterialTheme.typography.titleMedium)
                    if (s.byCategory.isEmpty()) {
                        Text("No spending this month.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            Donut(s.byCategory, Modifier.size(180.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Total", style = MaterialTheme.typography.labelMedium)
                                Text(Money.format(s.byCategory.sumOf { it.cents }), fontWeight = FontWeight.Bold)
                            }
                        }
                        val total = s.byCategory.sumOf { it.cents }.coerceAtLeast(1)
                        s.byCategory.forEach { sl ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(Color(sl.color), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text("${sl.emoji} ${sl.label}", Modifier.weight(1f))
                                Text("${(sl.cents * 100 / total)}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Text(Money.format(sl.cents), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("By payment method", style = MaterialTheme.typography.titleMedium)
                    val max = s.byMethod.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                    s.byMethod.forEach { (m, cents) ->
                        Column(Modifier.padding(top = 10.dp)) {
                            Row {
                                Text(m.label, Modifier.weight(1f))
                                Text(Money.format(cents), fontWeight = FontWeight.Medium)
                            }
                            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                                Box(Modifier.fillMaxWidth(cents.toFloat() / max).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                            }
                        }
                    }
                    if (s.byMethod.isEmpty()) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Daily spending", style = MaterialTheme.typography.titleMedium)
                    DailyBars(s.daily, Modifier.fillMaxWidth().height(140.dp).padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("1", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        Text("${s.daily.size}", style = MaterialTheme.typography.labelSmall)
                    }
                    val max = s.daily.maxOrNull() ?: 0
                    if (max > 0) Text("Peak day: ${Money.format(max)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun Donut(slices: List<Slice>, modifier: Modifier) {
    val total = slices.sumOf { it.cents }.toFloat().coerceAtLeast(1f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val stroke = size.minDimension * 0.16f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        var start = -90f
        slices.forEach { s ->
            val sweep = 360f * s.cents / total
            drawArc(Color(s.color), start, (sweep - 1.5f).coerceAtLeast(0.5f), false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            start += sweep
        }
    }
}

@Composable
private fun DailyBars(values: List<Long>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        val barW = slot * 0.7f
        values.forEachIndexed { i, v ->
            val h = size.height * (v / max)
            if (v > 0) {
                drawRoundRect(
                    color,
                    topLeft = Offset(i * slot + (slot - barW) / 2, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 3, barW / 3),
                )
            }
        }
    }
}
