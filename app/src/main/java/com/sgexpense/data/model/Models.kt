package com.sgexpense.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PaymentMethod(val label: String) {
    PAYNOW("PayNow"),
    GPAY_TAP("GPay tap"),
    SHOPBACK_PAY("ShopBack"),
    FAIRPRICE_PAY("FairPrice Pay"),
    SIMPLYGO("SimplyGo (Bus/MRT)"),
    CARD("Card"),
    CASH("Cash"),
    BANK_TRANSFER("Bank transfer"),
    OTHER("Other");

    /** Higher = more specific; used when merging duplicate captures. */
    val specificity: Int
        get() = when (this) {
            GPAY_TAP, SHOPBACK_PAY, FAIRPRICE_PAY, SIMPLYGO -> 4
            PAYNOW -> 3
            CARD, CASH -> 2
            BANK_TRANSFER -> 1
            OTHER -> 0
        }
}

enum class TxType(val label: String) {
    EXPENSE("Expense"),
    INCOME("Income"),
    REFUND("Refund");

    /** Sign applied when summing spend: expenses count positive. */
    val spendSign: Int get() = if (this == EXPENSE) 1 else -1
}

enum class Source(val label: String) {
    MANUAL("Manual"),
    NOTIFICATION("Auto-captured"),
    OCR("Image import"),
    IMPORT("File import"),
}

@Entity(
    tableName = "transactions",
    indices = [Index("occurredAt"), Index("categoryId")],
)
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountCents: Long,
    val currency: String = "SGD",
    val type: TxType = TxType.EXPENSE,
    val merchant: String = "",
    val categoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    val account: String? = null,
    val occurredAt: Long,
    val note: String = "",
    val cashbackCents: Long = 0,
    val source: Source = Source.MANUAL,
    /** Package name of the app whose notification created this (auto-capture only). */
    val sourceApp: String? = null,
    val rawText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val color: Long,
    val isIncome: Boolean = false,
    val sortOrder: Int = 0,
)

/** Merchant keyword (normalised, upper case) → category. */
@Entity(tableName = "merchant_rules", indices = [Index(value = ["pattern"], unique = true)])
data class MerchantRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val categoryId: Long,
    val learned: Boolean = false,
)

/** Raw notifications from watched apps, kept for 30 days so parsers can be tuned. */
@Entity(tableName = "capture_log", indices = [Index("postedAt")])
data class CaptureLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** PARSED, MERGED, IGNORED, DUPLICATE_POST, CASHBACK */
    val outcome: String,
    val transactionId: Long? = null,
)
