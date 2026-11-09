package com.sgexpense.capture

import com.sgexpense.capture.parsers.ParserRegistry
import com.sgexpense.data.db.AppDatabase
import com.sgexpense.data.model.CaptureLog
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.repository.ExpenseRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Turns a posted notification into a saved (or merged) transaction, and logs it. */
class CaptureProcessor(
    private val db: AppDatabase,
    private val repo: ExpenseRepository,
) {
    private val mutex = Mutex()

    suspend fun process(pkg: String, title: String, text: String, postedAt: Long) = mutex.withLock {
        val logDao = db.captureLog()
        // Apps re-post the same notification when it updates; ignore exact repeats within 5 min.
        if (logDao.countSame(pkg, title, text, postedAt - 5 * 60_000) > 0) return@withLock
        val raw = listOf(title, text).filter { it.isNotBlank() }.joinToString("\n")

        val parsed = ParserRegistry.parse(pkg, title, text)
        if (parsed == null) {
            logDao.insert(CaptureLog(packageName = pkg, title = title, text = text, postedAt = postedAt, outcome = OUTCOME_IGNORED))
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
        logDao.deleteOlderThan(postedAt - 30L * 24 * 3600_000)
    }

    companion object {
        const val OUTCOME_PARSED = "PARSED"
        const val OUTCOME_MERGED = "MERGED"
        const val OUTCOME_IGNORED = "IGNORED"
        const val OUTCOME_CASHBACK = "CASHBACK"
    }
}
