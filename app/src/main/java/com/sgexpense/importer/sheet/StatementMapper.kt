package com.sgexpense.importer.sheet

import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.TxType
import com.sgexpense.importer.DraftTransaction
import com.sgexpense.importer.MethodGuesser
import com.sgexpense.util.DateParsing
import com.sgexpense.util.Money
import java.time.LocalDate
import java.util.Locale

/** Which columns hold what. -1 = not present. */
data class ColumnMapping(
    val headerRow: Int,
    val date: Int,
    val description: Int,
    val amount: Int = -1,
    val withdrawal: Int = -1,
    val deposit: Int = -1,
    /** For a single signed amount column: true when positive numbers are spending (card statements). */
    val positiveIsExpense: Boolean = true,
    val isCardStatement: Boolean = false,
) {
    val isValid get() = date >= 0 && (amount >= 0 || withdrawal >= 0 || deposit >= 0)
}

/**
 * Maps bank exports to transactions. Tuned for UOB:
 *  - Account history: "Transaction Date | Transaction Description | Withdrawal | Deposit | Available Balance"
 *  - Card statement:  "Transaction Date | Posting Date | Description | ... | Transaction Amount(Local)"
 * and falls back to keyword detection for any similar CSV/Excel.
 */
object StatementMapper {
    private fun norm(s: String) = s.lowercase(Locale.ENGLISH).replace(Regex("""[^a-z()]+"""), " ").trim()

    fun detect(sheet: SheetData): ColumnMapping? {
        for (r in 0 until minOf(sheet.rows.size, 40)) {
            val headers = sheet.rows[r].map(::norm)
            val date = headers.indexOfFirst { it == "transaction date" || it == "trans date" || it == "txn date" }
                .takeIf { it >= 0 } ?: headers.indexOfFirst { it == "date" || it.endsWith(" date") && !it.startsWith("posting") && !it.startsWith("value") }
            if (date < 0) continue
            val desc = headers.indexOfFirst {
                it.contains("description") || it.contains("details") || it.contains("particulars") || it.contains("merchant") ||
                    it == "transaction" || it.contains("narrative") || it.contains("remarks") || it.contains("payee")
            }
            val withdrawal = headers.indexOfFirst { it.contains("withdrawal") || it == "debit" || it.startsWith("debit ") || it.contains("money out") }
            val deposit = headers.indexOfFirst { it.contains("deposit") || it == "credit" || it.startsWith("credit ") && !it.contains("card") || it.contains("money in") }
            val amountCandidates = headers.withIndex().filter { (_, h) -> h.contains("amount") }
            val amount = (amountCandidates.firstOrNull { it.value.contains("local") || it.value.contains("sgd") } ?: amountCandidates.firstOrNull())?.index ?: -1
            if (withdrawal < 0 && deposit < 0 && amount < 0) continue
            val isCard = headers.any { it.contains("posting date") } || headers.any { it.contains("card") }
            val base = ColumnMapping(r, date, desc, amount, withdrawal, deposit, true, isCard)
            return if (amount >= 0 && withdrawal < 0) base.copy(positiveIsExpense = guessPositiveIsExpense(sheet, base)) else base
        }
        return null
    }

    /**
     * Card statements list spending as positive numbers (refunds marked "CR"). Bank exports with a single
     * signed column use a minus sign or brackets for money going out.
     */
    private fun guessPositiveIsExpense(sheet: SheetData, m: ColumnMapping): Boolean {
        if (m.isCardStatement) return true
        val hasExplicitNegatives = (m.headerRow + 1 until sheet.rows.size).any {
            val c = sheet.cell(it, m.amount)
            (c.startsWith("-") || c.startsWith("(") || c.startsWith("−")) && Money.parseSignedCents(c) != null
        }
        return !hasExplicitNegatives
    }

    fun map(sheet: SheetData, m: ColumnMapping, today: LocalDate): List<DraftTransaction> {
        val out = mutableListOf<DraftTransaction>()
        for (r in m.headerRow + 1 until sheet.rows.size) {
            val date = DateParsing.findDate(sheet.cell(r, m.date), today) ?: continue
            val desc = if (m.description >= 0) sheet.cell(r, m.description) else ""
            val (cents, type) = amountFor(sheet, r, m) ?: continue
            if (cents == 0L) continue
            val rowText = sheet.rows[r].joinToString(" ")
            out += DraftTransaction(
                date = date,
                time = DateParsing.findTime(sheet.cell(r, m.date)),
                amountCents = cents,
                type = type,
                merchant = cleanDescription(desc),
                paymentMethod = MethodGuesser.guess(desc, if (m.isCardStatement) PaymentMethod.CARD else PaymentMethod.OTHER),
                raw = rowText.take(500),
            )
        }
        return out
    }

    private fun amountFor(sheet: SheetData, r: Int, m: ColumnMapping): Pair<Long, TxType>? {
        if (m.withdrawal >= 0 || m.deposit >= 0) {
            val w = if (m.withdrawal >= 0) Money.parseCents(sheet.cell(r, m.withdrawal)) else null
            val d = if (m.deposit >= 0) Money.parseCents(sheet.cell(r, m.deposit)) else null
            return when {
                w != null && w > 0 -> w to TxType.EXPENSE
                d != null && d > 0 -> d to TxType.INCOME
                m.amount >= 0 -> signed(sheet.cell(r, m.amount), m)
                else -> null
            }
        }
        return signed(sheet.cell(r, m.amount), m)
    }

    private fun signed(cell: String, m: ColumnMapping): Pair<Long, TxType>? {
        val v = Money.parseSignedCents(cell) ?: return null
        val expense = if (m.positiveIsExpense) v > 0 else v < 0
        val creditType = if (m.isCardStatement) TxType.REFUND else TxType.INCOME
        return kotlin.math.abs(v) to (if (expense) TxType.EXPENSE else creditType)
    }

    private val PREFIXES = Regex(
        """(?i)^(paynow[- ]?(fast|transfer|othr|other)?|fast (payment|transfer)|fast|othr|nets( qr)?|pos|debit purchase|purchase|bill payment|""" +
            """inward|outward|funds? transfer|misc dr|misc cr|giro)\b[\s:\-]*"""
    )

    fun cleanDescription(s: String): String {
        var t = s.replace(Regex("""\s+"""), " ").trim()
        repeat(3) { t = t.replace(PREFIXES, "").trim() }
        t = t.replace(Regex("""(?i)\b(OTHR|OTHER|SGP|SINGAPORE SG|SG)$"""), "").trim()
        return t.ifBlank { s.trim() }.take(80)
    }
}
