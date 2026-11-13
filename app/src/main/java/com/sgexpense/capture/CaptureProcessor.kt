package com.sgexpense.capture

import com.sgexpense.capture.parsers.MissingAmountHint
import com.sgexpense.capture.parsers.ParserRegistry
import com.sgexpense.capture.parsers.UobMissingAmount
import com.sgexpense.data.db.AppDatabase
import com.sgexpense.data.model.CaptureLog
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.repository.ExpenseRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Turns a posted notification into a saved (or merged) transaction, and logs it. */
class CaptureProcessor(
    private val db: AppDatabase,
    private val repo: ExpenseRepository,
    private val promptEnabled: () -> Boolean = { true },
    private val onNeedsAmount: (logId: Long, hint: MissingAmountHint, postedAt: Long) -> Unit = { _, _, _ -> },
    private val onPromptResolved: (logId: Long) -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun process(pkg: String, title: String, text: String, postedAt: Long) = mutex.withLock {
        val logDao = db.captureLog()
        // Apps re-post the same notification when it updates; ignore exact repeats within 5 min.
        if (logDao.countSame(pkg, title, text, postedAt - 5 * 60_000) > 0) return@withLock
        val raw = listOf(title, text).filter { it.isNotBlank() }.joinToString("\n")

        val parsed = ParserRegistry.parse(pkg, title, text)
        if (parsed == null) {
            val outcome = missingAmountOutcome(pkg, title, text, postedAt)
            val logId = logDao.insert(CaptureLog(packageName = pkg, title = title, text = text, postedAt = postedAt, outcome = outcome))
            if (outcome == OUTCOME_NEEDS_AMOUNT) {
                UobMissingAmount.hint(title, text)?.let { onNeedsAmount(logId, it, postedAt) }
            }
            return@withLock
        }

        val txDao = db.transactions()
        if (parsed.cashbackOnly) {
            val since = postedAt - 3L * 24 * 3600_000
            val target = txDao.shopBackSince(since).firstOrNull { it.cashbackCents == 0L }
            if (target != null) txDao.update(target.copy(cashbackCents = parsed.amountCents))
            logDao.insert(CaptureLog(packageName = pkg, title = title, text = text, postedAt = postedAt, outcome = OUTCOME_CASHBACK, transactionId = target?.id))
            return@withLock
        }

        val nearby = txDao.capturedBetween(postedAt - Deduplicator.WINDOW_MS, postedAt + Deduplicator.WINDOW_MS)
        val match = Deduplicator.findMatch(parsed, postedAt, pkg, nearby)
        if (match != null) {
            var merged = Deduplicator.merge(match, parsed, raw)
            if (merged.categoryId == null || merged.merchant != match.merchant) {
                merged = merged.copy(categoryId = repo.categorize(merged.merchant, merged.type, merged.paymentMethod) ?: merged.categoryId)
            }
            txDao.update(merged)
            logDao.insert(CaptureLog(packageName = pkg, title = title, text = text, postedAt = postedAt, outcome = OUTCOME_MERGED, transactionId = match.id))
            return@withLock
        }

        val tx = Transaction(
            amountCents = parsed.amountCents,
            type = parsed.type,
            merchant = parsed.merchant,
            paymentMethod = parsed.paymentMethod,
            account = parsed.account,
            occurredAt = postedAt,
            source = Source.NOTIFICATION,
            sourceApp = pkg,
            rawText = raw.take(2000),
            categoryId = repo.categorize(parsed.merchant, parsed.type, parsed.paymentMethod),
        )
        val id = txDao.insert(tx)
        logDao.insert(CaptureLog(packageName = pkg, title = title, text = text, postedAt = postedAt, outcome = OUTCOME_PARSED, transactionId = id))
        resolvePromptsCoveredBy(tx)
        logDao.deleteOlderThan(postedAt - 30L * 24 * 3600_000)
    }

    /**
     * A UOB alert with no amount (typical for PayNow) becomes a prompt, unless:
     *  - prompts are switched off,
     *  - another prompt from the same app arrived in the last minute (UOB sometimes posts two per transfer), or
     *  - it's a card payment another app (e.g. Google Wallet) already captured with an amount.
     */
    private suspend fun missingAmountOutcome(pkg: String, title: String, text: String, postedAt: Long): String {
        if (!promptEnabled() || !pkg.contains("uob", ignoreCase = true)) return OUTCOME_IGNORED
        val hint = UobMissingAmount.hint(title, text) ?: return OUTCOME_IGNORED
        if (db.captureLog().countPendingSince(pkg, postedAt - 60_000) > 0) return OUTCOME_DUPLICATE_PROMPT
        val from = postedAt - Deduplicator.WINDOW_MS
        val to = postedAt + Deduplicator.WINDOW_MS
        val fromAlerts = db.captureLog().parsedTxIdsBetween(from, to).toSet()
        val nearby = db.transactions().capturedBetween(from, to).filter { it.id in fromAlerts }
        val covered = nearby.any { tx ->
            tx.type == hint.type && when {
                // A card payment another app (e.g. Google Wallet) already captured with its amount.
                hint.paymentMethod == PaymentMethod.CARD -> tx.sourceApp != pkg
                // UOB's detailed alert ("transfer of SGD 50.00 to …") for the same transfer, a few minutes apart.
                else -> isSameTransfer(tx, pkg, hint, postedAt)
            }
        }
        return if (covered) OUTCOME_COVERED else OUTCOME_NEEDS_AMOUNT
    }

    private fun isSameTransfer(tx: Transaction, pkg: String, hint: MissingAmountHint, postedAt: Long) =
        tx.sourceApp == pkg && tx.paymentMethod == hint.paymentMethod && tx.type == hint.type &&
            kotlin.math.abs(tx.occurredAt - postedAt) <= SAME_TRANSFER_MS &&
            PayeeMatcher.samePayee(tx.merchant, hint.merchant)

    /** The amount alert arrived after the "transfer successful" one: the prompt is no longer needed. */
    private suspend fun resolvePromptsCoveredBy(tx: Transaction) {
        val pkg = tx.sourceApp ?: return
        db.captureLog().pendingBetween(pkg, tx.occurredAt - SAME_TRANSFER_MS, tx.occurredAt + SAME_TRANSFER_MS)
            .filter { log -> UobMissingAmount.hint(log.title, log.text)?.let { isSameTransfer(tx, pkg, it, log.postedAt) } == true }
            .forEach { log ->
                db.captureLog().setOutcome(log.id, OUTCOME_COVERED, null)
                onPromptResolved(log.id)
            }
    }

    companion object {
        const val OUTCOME_PARSED = "PARSED"
        const val OUTCOME_MERGED = "MERGED"
        const val OUTCOME_IGNORED = "IGNORED"
        const val OUTCOME_CASHBACK = "CASHBACK"
        /** No amount in the alert; waiting for the user to type it. */
        const val OUTCOME_NEEDS_AMOUNT = "NEEDS_AMOUNT"
        const val OUTCOME_COMPLETED = "COMPLETED"
        const val OUTCOME_DISMISSED = "DISMISSED"
        const val OUTCOME_DUPLICATE_PROMPT = "DUPLICATE_PROMPT"
        const val OUTCOME_COVERED = "COVERED"

        /** UOB's "with amount" and "transfer successful" alerts for one transfer arrive within a few minutes. */
        const val SAME_TRANSFER_MS = 3 * 60_000L
    }
}
