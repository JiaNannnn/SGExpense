package com.sgexpense.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sgexpense.data.model.CaptureLog
import com.sgexpense.data.model.Category
import com.sgexpense.data.model.MerchantRule
import com.sgexpense.data.model.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(tx: Transaction): Long

    @Insert
    suspend fun insertAll(txs: List<Transaction>): List<Long>

    @Update
    suspend fun update(tx: Transaction)

    @Delete
    suspend fun delete(tx: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC")
    fun observeRange(from: Long, to: Long): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE source = 'NOTIFICATION' AND createdAt >= :since ORDER BY createdAt DESC")
    fun observeCapturedSince(since: Long): Flow<List<Transaction>>

    @Query(
        "SELECT * FROM transactions WHERE amountCents = :amount AND occurredAt >= :from AND occurredAt <= :to"
    )
    suspend fun findByAmountBetween(amount: Long, from: Long, to: Long): List<Transaction>

    @Query("SELECT * FROM transactions WHERE source = 'NOTIFICATION' AND occurredAt >= :from AND occurredAt <= :to")
    suspend fun capturedBetween(from: Long, to: Long): List<Transaction>

    @Query("SELECT * FROM transactions WHERE paymentMethod = 'SHOPBACK_PAY' AND occurredAt >= :since ORDER BY occurredAt DESC")
    suspend fun shopBackSince(since: Long): List<Transaction>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun getAll(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt")
    suspend fun getRange(from: Long, to: Long): List<Transaction>

    @Query("UPDATE transactions SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isIncome, sortOrder, name")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY isIncome, sortOrder, name")
    suspend fun getAll(): List<Category>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert
    suspend fun insert(c: Category): Long

    @Insert
    suspend fun insertAll(cs: List<Category>): List<Long>

    @Update
    suspend fun update(c: Category)

    @Delete
    suspend fun delete(c: Category)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface MerchantRuleDao {
    @Query("SELECT * FROM merchant_rules")
    suspend fun getAll(): List<MerchantRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: MerchantRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<MerchantRule>)

    @Query("DELETE FROM merchant_rules WHERE categoryId = :categoryId")
    suspend fun deleteForCategory(categoryId: Long)

    @Query("DELETE FROM merchant_rules")
    suspend fun deleteAll()
}

@Dao
interface CaptureLogDao {
    @Insert
    suspend fun insert(log: CaptureLog): Long

    @Query("SELECT * FROM capture_log ORDER BY postedAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<CaptureLog>>

    @Query("SELECT COUNT(*) FROM capture_log WHERE packageName = :pkg AND title = :title AND text = :text AND postedAt >= :since")
    suspend fun countSame(pkg: String, title: String, text: String, since: Long): Int

    @Query("DELETE FROM capture_log WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM capture_log")
    suspend fun deleteAll()
}
