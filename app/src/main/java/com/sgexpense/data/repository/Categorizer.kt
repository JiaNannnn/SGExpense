package com.sgexpense.data.repository

import com.sgexpense.data.model.MerchantRule
import java.util.Locale

/** Matches merchant names against keyword rules. Pure logic, unit-tested. */
object Categorizer {
    private val NOISE_PREFIXES = listOf(
        // Punctuation is already turned into spaces, so "PAYNOW-FAST" arrives as "PAYNOW FAST".
        "PAYNOW FAST", "PAYNOW TRANSFER", "PAYNOW", "FAST PAYMENT", "FAST TRANSFER", "FAST", "OTHR", "NETS QR", "NETS", "POS",
        "DEBIT PURCHASE", "PURCHASE", "VISA", "MASTERCARD", "BILL PAYMENT", "INWARD", "OUTWARD", "TRF",
    )
    private val NON_ALNUM = Regex("""[^A-Z0-9&./ ]""")
    private val SPACES = Regex("""\s+""")
    private val TRAILING_NOISE = Regex("""\b(SINGAPORE|SGP|SG|PTE|LTD|PTE LTD)\b""")

    /** Upper-case, strips punctuation, payment-rail prefixes and "SINGAPORE SG" suffixes. */
    fun normalize(merchant: String): String {
        var s = merchant.uppercase(Locale.ENGLISH).replace(NON_ALNUM, " ").replace(SPACES, " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (p in NOISE_PREFIXES) {
                if (s.startsWith("$p ")) {
                    s = s.removePrefix(p).trim(); changed = true
                }
            }
        }
        s = s.replace(TRAILING_NOISE, " ").replace(SPACES, " ").trim()
        return s
    }

    /** The key learned from a merchant: its first two meaningful words. */
    fun learnKey(merchant: String): String? {
        val words = normalize(merchant).split(' ').filter { it.length >= 2 && it.any(Char::isLetter) }
        if (words.isEmpty()) return null
        return words.take(2).joinToString(" ")
    }

    /** Best rule for a merchant: learned rules beat defaults, then the longest pattern wins. */
    fun match(merchant: String, rules: List<MerchantRule>): MerchantRule? {
        if (merchant.isBlank()) return null
        val n = " ${normalize(merchant)} "
        val raw = " ${merchant.uppercase(Locale.ENGLISH)} "
        return rules
            .filter { r ->
                val p = r.pattern.trim()
                p.isNotEmpty() && (containsWord(n, p) || containsWord(raw, p))
            }
            .maxWithOrNull(compareBy<MerchantRule> { it.learned }.thenBy { it.pattern.length })
    }

    /**
     * Pattern must start at a word boundary. Longer patterns may be a prefix ("GRAB" in "GRABTAXI"),
     * but short ones (≤ 4 chars like "BUS", "MRT", "KFC") must be whole words so "BUSINESS" isn't transport.
     */
    private fun containsWord(haystack: String, pattern: String): Boolean {
        var idx = haystack.indexOf(pattern)
        while (idx >= 0) {
            val before = haystack.getOrNull(idx - 1)
            val after = haystack.getOrNull(idx + pattern.length)
            val startOk = before == null || !before.isLetterOrDigit()
            val endOk = pattern.length > 4 || after == null || !after.isLetterOrDigit()
            if (startOk && endOk) return true
            idx = haystack.indexOf(pattern, idx + 1)
        }
        return false
    }
}
