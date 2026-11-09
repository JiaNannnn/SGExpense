package com.sgexpense.importer.ocr

import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.TxType
import com.sgexpense.importer.DraftTransaction
import com.sgexpense.importer.MethodGuesser
import com.sgexpense.util.DateParsing
import com.sgexpense.util.Money
import java.time.LocalDate

/** One recognised text line with its bounding box (pixels). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY get() = (top + bottom) / 2
    val height get() = (bottom - top).coerceAtLeast(1)
}

/**
 * ML Kit returns text in blocks, so a receipt's "TOTAL" and "12.50" often come back as separate lines.
 * This groups lines that sit at the same height into one visual row, left to right.
 */
object RowGrouper {
    fun group(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowCenter = row.map { it.centerY }.average()
                val rowHeight = row.map { it.height }.average()
                if (kotlin.math.abs(line.centerY - rowCenter) <= 0.5 * minOf(rowHeight, line.height.toDouble())) {
                    row.add(line); continue
                }
            }
            rows.add(mutableListOf(line))
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }
}

private val CURRENCY_AMOUNT = Regex("""(?i)([+\-−]?)\s?(?:S\$|SGD|SG\$|\$)\s?(${Money.NUMBER_PATTERN})(?![\d%]|\.\d)""")
/** "12.50" without a currency marker; the look-arounds keep dates like 07.11.26 out. */
private val DECIMAL_AMOUNT = Regex("""(?i)([+\-−]?)\s?(?<![\d.,])(\d{1,3}(?:,\d{3})*\.\d{2}|\d{4,}\.\d{2})(?![\d%]|\.\d)""")

/** Amount with sign: prefers values with a currency marker, else values with 2 decimals. */
internal fun findAmount(row: String): Pair<Long, Boolean>? {
    val m = CURRENCY_AMOUNT.findAll(row).lastOrNull() ?: DECIMAL_AMOUNT.findAll(row).lastOrNull() ?: return null
    val cents = Money.parseCents(m.groupValues[2]) ?: return null
    if (cents == 0L) return null
    val positive = m.groupValues[1] == "+"
    return cents to positive
}

internal fun stripAmounts(row: String): String =
    row.replace(CURRENCY_AMOUNT, " ").replace(DECIMAL_AMOUNT, " ").replace(Regex("""\s+"""), " ").trim()

/** Receipt photo → one transaction (the total). */
object ReceiptParser {
    private val TOTAL_STRONG = Regex("""(?i)\b(grand\s*total|nett?\s*total|total\s*(amount|payable|due|sgd|s\$)|amount\s*(due|payable)|total\s*incl|net\s*amount|balance\s*due)\b""")
    private val TOTAL = Regex("""(?i)\btotal\b""")
    private val NOT_TOTAL = Regex("""(?i)\b(sub\s*-?\s*total|total\s*(qty|quantity|items?|savings?|discount)|gst|service\s*charge|svc|change|tendered|rounding)\b""")
    private val HEADER_NOISE = Regex("""(?i)(receipt|tax invoice|invoice|gst\s*reg|uen|co\.?\s*reg|tel|phone|fax|www\.|\.com|singapore\s*\d{6}|#\d{2}-\d{2}|\d{6}$|cashier|table|order|welcome|thank)""")
    private val RECEIPT_WORDS = Regex("""(?i)\b(total|subtotal|sub total|gst|tax invoice|receipt|qty|cashier|change|service charge|nett)\b""")

    fun looksLikeReceipt(rows: List<String>): Boolean = rows.count { RECEIPT_WORDS.containsMatchIn(it) } >= 2

    fun parse(rows: List<String>, today: LocalDate): DraftTransaction? {
        val amount = findTotal(rows) ?: return null
        val all = rows.joinToString("\n")
        val date = rows.firstNotNullOfOrNull { DateParsing.findDate(it, today) } ?: today
        val time = rows.firstNotNullOfOrNull { DateParsing.findTime(it) }
        return DraftTransaction(
            date = date,
            time = time,
            amountCents = amount,
            merchant = findMerchant(rows),
            paymentMethod = MethodGuesser.guess(all, PaymentMethod.OTHER),
            raw = all.take(2000),
        )
    }

    internal fun findTotal(rows: List<String>): Long? {
        fun amountNear(i: Int): Long? =
            findAmount(stripLabel(rows[i]))?.first ?: rows.getOrNull(i + 1)?.takeIf { !it.any(Char::isLetter) }?.let { findAmount(it)?.first }

        fun notTotal(i: Int) = NOT_TOTAL.containsMatchIn(stripLabel(rows[i]))
        for (i in rows.indices) {
            if (TOTAL_STRONG.containsMatchIn(rows[i]) && !notTotal(i)) amountNear(i)?.let { return it }
        }
        // Last plain "TOTAL" row (receipts often print subtotal-ish totals earlier)
        for (i in rows.indices.reversed()) {
            if (TOTAL.containsMatchIn(rows[i]) && !notTotal(i)) amountNear(i)?.let { return it }
        }
        // Fallback: largest decimal amount on the receipt
        return rows.mapNotNull { DECIMAL_AMOUNT.findAll(it).mapNotNull { m -> Money.parseCents(m.groupValues[2]) }.maxOrNull() }.maxOrNull()
    }

    private fun stripLabel(row: String) = row.replace(Regex("""(?i)\(?incl(?:usive)?\.?\s*(?:of\s*)?gst\)?"""), " ")

    internal fun findMerchant(rows: List<String>): String {
        for (row in rows.take(6)) {
            val t = row.trim()
            val letters = t.count(Char::isLetter)
            if (letters < 3) continue
            if (HEADER_NOISE.containsMatchIn(t)) continue
            if (findAmount(t) != null && letters < 6) continue
            return t.replace(Regex("""\s+"""), " ").take(60)
        }
        return ""
    }
}

/**
 * Screenshot of a transaction list or a payment confirmation → one or more transactions.
 * Handles layouts like:
 *   07 Nov 2026                      (date header)
 *   FairPrice Xtra       -S$12.50
 *   Grab                  -S$8.40
 * or "Transfer successful / SGD 50.00 / To JOHN TAN / 7 Nov 2026, 7:15 PM".
 */
object ScreenshotListParser {
    private val SKIP = Regex("""(?i)\b(balance|available|total|ledger|limit|points|rewards?|cashback earned|exchange rate|fee\s*s\$0)\b""")
    private val INCOME_WORDS = Regex("""(?i)\b(received|incoming|credited|refund|cashback|from)\b""")
    private val TO_LINE = Regex("""(?i)^(?:to|paid to|recipient|payee|merchant|sent to|transfer to)\s*:?\s*(.+)$""")
    private val FROM_LINE = Regex("""(?i)^(?:from|sender|received from)\s*:?\s*(.+)$""")
    private val UI_NOISE = Regex("""(?i)^(transaction history|history|activity|transactions|recent|all|filter|search|see all|done|back|share|details|status|successful|completed|paid|pending|payment|transfer|reference.*|ref.*|\d{1,2}:\d{2}.*)$""")

    fun parse(rows: List<String>, today: LocalDate): List<DraftTransaction> {
        val clean = rows.map { it.trim() }.filter { it.isNotEmpty() }
        val amountRows = clean.indices.filter { i -> findAmount(clean[i]) != null && !SKIP.containsMatchIn(clean[i]) }
        if (amountRows.isEmpty()) return emptyList()
        if (amountRows.size == 1) single(clean, amountRows[0], today)?.let { return listOf(it) }
        return list(clean, amountRows, today)
    }

    /** A single payment confirmation screen. */
    private fun single(rows: List<String>, amountIdx: Int, today: LocalDate): DraftTransaction? {
        val (cents, plus) = findAmount(rows[amountIdx]) ?: return null
        val all = rows.joinToString("\n")
        val to = rows.firstNotNullOfOrNull { TO_LINE.find(it)?.groupValues?.get(1) }
            ?: rows.indexOfFirst { Regex("""(?i)^(to|paid to|recipient)$""").matches(it) }.takeIf { it >= 0 }?.let { rows.getOrNull(it + 1) }
        val from = rows.firstNotNullOfOrNull { FROM_LINE.find(it)?.groupValues?.get(1) }
        val incoming = plus || (from != null && to == null) || Regex("""(?i)\b(received|incoming|credited)\b""").containsMatchIn(all)
        val merchant = (if (incoming) from else to)
            ?: stripAmounts(rows[amountIdx]).takeIf { it.count(Char::isLetter) >= 3 }
            ?: rows.getOrNull(amountIdx - 1)?.takeIf { !UI_NOISE.matches(it) && findAmount(it) == null }
            ?: ""
        return DraftTransaction(
            date = rows.firstNotNullOfOrNull { DateParsing.findDate(it, today) } ?: today,
            time = rows.firstNotNullOfOrNull { DateParsing.findTime(it) },
            amountCents = cents,
            type = if (incoming) TxType.INCOME else TxType.EXPENSE,
            merchant = cleanName(merchant),
            paymentMethod = MethodGuesser.guess(all),
            raw = all.take(2000),
        )
    }

    private fun list(rows: List<String>, amountRows: List<Int>, today: LocalDate): List<DraftTransaction> {
        val all = rows.joinToString("\n")
        val screenMethod = MethodGuesser.guess(all)
        val out = mutableListOf<DraftTransaction>()
        var headerDate: LocalDate? = null
        var lastAmountIdx = -1
        val amountSet = amountRows.toSet()
        for (i in rows.indices) {
            val row = rows[i]
            if (i !in amountSet) {
                // A row that is only a date acts as a section header for the rows below.
                val d = DateParsing.findDate(row, today)
                if (d != null && DateParsing.stripDates(row).count(Char::isLetter) <= 3) headerDate = d
                continue
            }
            val (cents, plus) = findAmount(row) ?: continue
            val rest = DateParsing.stripDates(stripAmounts(row))
            // Description: text on the same row, else the nearest non-amount row above (after the previous item).
            var desc = rest.takeIf { it.count(Char::isLetter) >= 2 }
            var descIdx = i
            if (desc == null) {
                for (j in i - 1 downTo lastAmountIdx + 1) {
                    val r = rows[j]
                    if (UI_NOISE.matches(r)) continue
                    val stripped = DateParsing.stripDates(r)
                    if (stripped.count(Char::isLetter) >= 2) { desc = stripped; descIdx = j; break }
                }
            }
            // Date: on this row, the row below (if it's this item's detail line, e.g. "7 Nov, 8:15 PM"), the row above, or the header.
            // A bare date row below is the next section's header, not this item's date.
            val below = rows.getOrNull(i + 1)?.takeIf { b ->
                i + 1 !in amountSet && (DateParsing.findTime(b) != null || DateParsing.stripDates(b).count(Char::isLetter) > 3)
            }
            val date = DateParsing.findDate(row, today)
                ?: below?.let { DateParsing.findDate(it, today) }
                ?: rows.getOrNull(descIdx)?.let { DateParsing.findDate(it, today) }
                ?: headerDate
                ?: today
            val time = DateParsing.findTime(row) ?: below?.let { DateParsing.findTime(it) }
            val context = listOfNotNull(desc, row, below).joinToString(" ")
            val incoming = plus || (INCOME_WORDS.containsMatchIn(context) && !row.contains('-'))
            out += DraftTransaction(
                date = date,
                time = time,
                amountCents = cents,
                type = if (incoming) TxType.INCOME else TxType.EXPENSE,
                merchant = cleanName(desc ?: ""),
                paymentMethod = MethodGuesser.guess(context, screenMethod),
                raw = context.take(500),
            )
            lastAmountIdx = i
        }
        return out
    }

    private fun cleanName(s: String) = s
        .replace(Regex("""(?i)^(to|paid to|from|sent to)\s*:?\s*"""), "")
        .replace(Regex("""[\s\-–•·|>]+$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(60)
}

/** Decides whether OCR text is a receipt or a screenshot, and parses accordingly. */
object OcrAnalyzer {
    fun analyze(rows: List<String>, today: LocalDate): List<DraftTransaction> {
        if (ReceiptParser.looksLikeReceipt(rows)) {
            ReceiptParser.parse(rows, today)?.let { return listOf(it) }
        }
        return ScreenshotListParser.parse(rows, today)
    }
}
