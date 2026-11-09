package com.sgexpense.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sgexpense.AppContainer
import com.sgexpense.SgExpenseApp
import com.sgexpense.data.model.Category
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.model.TxType
import com.sgexpense.ui.theme.incomeColor
import com.sgexpense.util.DateParsing
import com.sgexpense.util.Money
import com.sgexpense.util.toLocalDateTime
import java.time.YearMonth

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as SgExpenseApp
    return viewModel(key = key, factory = viewModelFactory { initializer { create(app.container) } })
}

@Composable
fun CategoryDot(category: Category?, size: Int = 40) {
    val color = category?.let { Color(it.color) } ?: MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(size.dp).background(color.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(category?.emoji ?: "❔", fontSize = (size * 0.45).sp)
    }
}

@Composable
fun amountColor(type: TxType): Color =
    if (type == TxType.EXPENSE) MaterialTheme.colorScheme.onSurface else incomeColor()

fun signedAmount(tx: Transaction): String =
    (if (tx.type == TxType.EXPENSE) "-" else "+") + Money.format(tx.amountCents)

@Composable
fun TransactionRow(tx: Transaction, category: Category?, onClick: () -> Unit, showDate: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(category)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                tx.merchant.ifBlank { category?.name ?: "Unnamed" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val dt = tx.occurredAt.toLocalDateTime()
            val whenText = if (showDate) dt.format(DateParsing.displayDay) + " · " + dt.format(DateParsing.displayTime) else dt.format(DateParsing.displayTime)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tx.source == Source.NOTIFICATION) {
                    Icon(Icons.Filled.AutoAwesome, "Auto-captured", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    "$whenText · ${tx.paymentMethod.label}" + (category?.let { " · ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(signedAmount(tx), color = amountColor(tx.type), fontWeight = FontWeight.SemiBold)
            if (tx.cashbackCents > 0) {
                Text("+${Money.format(tx.cashbackCents)} cashback", style = MaterialTheme.typography.labelSmall, color = incomeColor())
            }
        }
    }
}

@Composable
fun MonthSwitcher(month: YearMonth, onChange: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { onChange(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
        Text(month.format(DateParsing.displayMonth), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
        IconButton(onClick = { onChange(month.plusMonths(1)) }, enabled = month < YearMonth.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Sum of spending (expenses minus refunds), ignoring income. */
fun spendCents(txs: List<Transaction>): Long =
    txs.sumOf { when (it.type) { TxType.EXPENSE -> it.amountCents; TxType.REFUND -> -it.amountCents; TxType.INCOME -> 0L } }

fun incomeCents(txs: List<Transaction>): Long = txs.filter { it.type == TxType.INCOME }.sumOf { it.amountCents }
