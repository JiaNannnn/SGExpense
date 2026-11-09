package com.sgexpense.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Amounts are stored as Long cents (SGD). */
object Money {
    private val symbols = DecimalFormatSymbols(Locale.ENGLISH)
    private val fmt = DecimalFormat("#,##0.00", symbols)
    private val plainFmt = DecimalFormat("0.00", symbols)

    /** A money number such as 1,234.56 / 12.50 / 12 */
    const val NUMBER_PATTERN = """\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?"""

    private val CR = Regex("""(?i)\bCR\b""")
    private val STRIP = Regex("""(?i)\b(SGD|SG|CR|DR)\b|S\$|\$|\s""")
    private val DIGITS = Regex("""\d+(\.\d+)?""")

    /**
     * Parses "S$1,234.50", "SGD 12.5", "-12.00", "(12.00)", "12.00 CR".
     * Returns signed cents; "CR", brackets and a leading minus give negative values.
     */
    fun parseSignedCents(raw: String?): Long? {
        if (raw == null) return null
        var s = raw.trim()
        if (s.isEmpty()) return null
        var negative = false
        if (CR.containsMatchIn(s)) negative = true
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        s = s.replace(STRIP, "")
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1) }
        if (s.startsWith("+")) s = s.substring(1)
        s = s.replace(",", "")
        if (!DIGITS.matches(s)) return null
        val cents = BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        return if (negative) -cents else cents
    }

    /** Unsigned parse; returns absolute cents. */
    fun parseCents(raw: String?): Long? = parseSignedCents(raw)?.let { kotlin.math.abs(it) }

    fun format(cents: Long, symbol: Boolean = true): String {
        val v = BigDecimal(cents).movePointLeft(2)
        val body = fmt.format(v.abs())
        val sign = if (cents < 0) "-" else ""
        return if (symbol) "${sign}S$$body" else "$sign$body"
    }

    /** "12.50" style, for edit fields and CSV export. */
    fun plain(cents: Long): String = plainFmt.format(BigDecimal(cents).movePointLeft(2))
}
