package com.sgexpense.capture

import com.sgexpense.capture.parsers.ParsedPayment
import com.sgexpense.capture.parsers.Patterns
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import kotlin.math.abs

/**
 * One GPay tap often produces two notifications: Google Wallet ("S$12.50 at FairPrice") and the
 * bank's card alert ("SGD 12.50 ... at NTUC FAIRPRICE"). Same amount + type within the window,
 * from a *different* app, is treated as the same payment and merged.
 */
object Deduplicator {
    const val WINDOW_MS = 10 * 60 * 1000L

    fun findMatch(p: ParsedPayment, at: Long, sourceApp: String, existing: List<Transaction>): Transaction? =
        existing
            .filter {
                it.source == Source.NOTIFICATION &&
                    it.amountCents == p.amountCents &&
                    it.type == p.type &&
                    it.sourceApp != sourceApp &&
                    abs(it.occurredAt - at) <= WINDOW_MS
            }
            .minByOrNull { abs(it.occurredAt - at) }

    fun merge(existing: Transaction, p: ParsedPayment, raw: String): Transaction {
        val merchant = betterMerchant(existing.merchant, p.merchant)
        val method = if (p.paymentMethod.specificity > existing.paymentMethod.specificity) p.paymentMethod else existing.paymentMethod
        return existing.copy(
            merchant = merchant,
            paymentMethod = method,
            account = existing.account ?: p.account,
            rawText = listOfNotNull(existing.rawText, raw).joinToString("\n---\n").take(2000),
        )
    }

    /** Prefers a real name over blank/generic, then the more descriptive (longer) one. */
    fun betterMerchant(a: String, b: String): String {
        val aOk = a.isNotBlank() && !Patterns.isGenericTitle(a)
        val bOk = b.isNotBlank() && !Patterns.isGenericTitle(b)
        return when {
            aOk && !bOk -> a
            bOk && !aOk -> b
            !aOk && !bOk -> a.ifBlank { b }
            else -> if (b.length > a.length) b else a
        }
    }
}
