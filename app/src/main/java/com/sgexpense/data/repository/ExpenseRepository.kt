package com.sgexpense.data.repository

import androidx.room.withTransaction
import com.sgexpense.data.db.AppDatabase
import com.sgexpense.data.model.Category
import com.sgexpense.data.model.MerchantRule
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.model.TxType
import com.sgexpense.util.toLocalDate
import com.sgexpense.util.startMillis
import kotlinx.coroutines.flow.Flow

class ExpenseRepository(private val db: AppDatabase) {
    private val txDao = db.transactions()
    private val catDao = db.categories()
    private val ruleDao = db.rules()

    val categories: Flow<List<Category>> = catDao.observeAll()

    fun observeRange(from: Long, to: Long) = txDao.observeRange(from, to)
    fun observeRecent(limit: Int) = txDao.observeRecent(limit)
    fun observeCapturedSince(since: Long) = txDao.observeCapturedSince(since)

    suspend fun get(id: Long) = txDao.getById(id)

    /** Seeds default categories + SG merchant keywords on first launch. */
    suspend fun seedIfEmpty() {
        if (catDao.count() > 0) {
            addNewDefaultRules()
            return
        }
        db.withTransaction {
            val ids = catDao.insertAll(Defaults.toEntities())
            val rules = Defaults.categories.flatMapIndexed { i, seed ->
                seed.keywords.map { MerchantRule(pattern = it.trim(), categoryId = ids[i]) }
            }
            ruleDao.insertAll(rules)
        }
    }

    /**
     * Keywords added to [Defaults] after the user's first launch: add any that are missing, without
     * touching rules the user has learned.
     */
    private suspend fun addNewDefaultRules() {
        val cats = catDao.getAll().associateBy { it.name }
        val existing = ruleDao.getAll().map { it.pattern }.toSet()
        val missing = Defaults.categories.flatMap { seed ->
            val catId = cats[seed.name]?.id ?: return@flatMap emptyList()
            seed.keywords.map { it.trim() }.filter { it !in existing }.map { MerchantRule(pattern = it, categoryId = catId) }
        }
        if (missing.isNotEmpty()) ruleDao.insertAll(missing)
    }

    /** Picks a category from merchant rules, falling back to Transfers / Income. */
    suspend fun categorize(merchant: String, type: TxType, method: PaymentMethod): Long? {
        val cats = catDao.getAll()
        Categorizer.match(merchant, ruleDao.getAll())?.let { rule ->
            val cat = cats.firstOrNull { it.id == rule.categoryId }
            if (cat != null && cat.isIncome == (type != TxType.EXPENSE)) return cat.id
        }
        return when {
            type == TxType.REFUND -> cats.firstOrNull { it.name == "Cashback & Refunds" }?.id
            type == TxType.INCOME -> cats.firstOrNull { it.name == Defaults.INCOME }?.id ?: cats.firstOrNull { it.isIncome }?.id
            method == PaymentMethod.SIMPLYGO -> cats.firstOrNull { it.name == "Transport" }?.id
            method == PaymentMethod.PAYNOW || method == PaymentMethod.BANK_TRANSFER -> cats.firstOrNull { it.name == Defaults.TRANSFERS }?.id
            else -> null
        }
    }

    /** Saves a new or edited transaction; if the user picked a category for a merchant, remember it. */
    suspend fun save(tx: Transaction, previous: Transaction? = null): Long {
        val id = if (tx.id == 0L) txDao.insert(tx) else { txDao.update(tx); tx.id }
        val categoryChanged = tx.categoryId != null && tx.categoryId != previous?.categoryId
        if (categoryChanged && tx.merchant.isNotBlank()) learn(tx.merchant, tx.categoryId!!)
        return id
    }

    suspend fun learn(merchant: String, categoryId: Long) {
        val key = Categorizer.learnKey(merchant) ?: return
        val existing = ruleDao.getAll().firstOrNull { it.pattern == key }
        ruleDao.upsert(MerchantRule(id = existing?.id ?: 0, pattern = key, categoryId = categoryId, learned = true))
    }

    suspend fun delete(id: Long) = txDao.deleteById(id)

    suspend fun restore(tx: Transaction) { txDao.insert(tx) }

    /** True if a transaction with the same amount exists within a day of [at]. */
    suspend fun isLikelyDuplicate(amountCents: Long, at: Long): Boolean {
        val day = at.toLocalDate()
        return txDao.findByAmountBetween(amountCents, day.minusDays(1).startMillis(), day.plusDays(2).startMillis()).isNotEmpty()
    }

    suspend fun insertAll(txs: List<Transaction>) = txDao.insertAll(txs)

    // Categories
    suspend fun addCategory(c: Category) = catDao.insert(c)
    suspend fun updateCategory(c: Category) = catDao.update(c)
    suspend fun deleteCategory(c: Category) = db.withTransaction {
        txDao.clearCategory(c.id)
        ruleDao.deleteForCategory(c.id)
        catDao.delete(c)
    }
}
