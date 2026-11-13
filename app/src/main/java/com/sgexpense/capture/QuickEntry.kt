package com.sgexpense.capture

import com.sgexpense.util.Money

/**
 * Parses what the user types into the amount prompt: "12.50 Koufu", "$8 grab", "Koufu 4.5", "7-Eleven 5.20".
 * Prefers a number with decimals; numbers that are part of a word ("7-Eleven", "S11") are not amounts.
 */
object QuickEntry {
    private val NUMBER = Regex(
        """(?i)(?<![\w.\-])(?:S\$|SGD\s?|\$)?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)(?![\w\-]|\.\d)"""
    )

    data class Entry(val amountCents: Long, val text: String)

    fun parse(input: String): Entry? {
        val matches = NUMBER.findAll(input).toList()
        val m = matches.firstOrNull { it.groupValues[1].contains('.') } ?: matches.firstOrNull() ?: return null
        val cents = Money.parseCents(m.groupValues[1]) ?: return null
        if (cents <= 0) return null
        val rest = input.removeRange(m.range)
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', ',', ':', '@', ' ')
        return Entry(cents, rest)
    }
}
