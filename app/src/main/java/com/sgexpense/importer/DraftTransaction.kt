package com.sgexpense.importer

import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.TxType
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** A transaction detected by OCR or a spreadsheet, before the user reviews it. */
data class DraftTransaction(
    val date: LocalDate,
    val time: LocalTime? = null,
    val amountCents: Long,
    val type: TxType = TxType.EXPENSE,
    val merchant: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    val raw: String = "",
)

object MethodGuesser {
    /** Guesses the payment rail from description text. */
    fun guess(text: String, fallback: PaymentMethod = PaymentMethod.OTHER): PaymentMethod {
        val t = text.uppercase(Locale.ENGLISH)
        return when {
            Regex("""PAY\s?NOW""").containsMatchIn(t) -> PaymentMethod.PAYNOW
            Regex("""BUS\s*/\s*MRT|SIMPLY\s?GO|TRANSIT\s?LINK|EZ-?LINK""").containsMatchIn(t) -> PaymentMethod.SIMPLYGO
            t.contains("SHOPBACK") -> PaymentMethod.SHOPBACK_PAY
            Regex("""FAIRPRICE\s?PAY|\bFPAY\b|SCAN\s?(&|AND)\s?GO""").containsMatchIn(t) -> PaymentMethod.FAIRPRICE_PAY
            t.contains("GOOGLE PAY") || t.contains("GPAY") || t.contains("GOOGLE WALLET") -> PaymentMethod.GPAY_TAP
            Regex("""\b(FAST|GIRO|IBG|FUND TRANSFER|FUNDS TRANSFER|TRANSFER|TRF)\b""").containsMatchIn(t) -> PaymentMethod.BANK_TRANSFER
            Regex("""\b(ATM|CASH WITHDRAWAL)\b""").containsMatchIn(t) -> PaymentMethod.CASH
            Regex("""\b(VISA|MASTERCARD|DEBIT CARD|CREDIT CARD|POS|NETS)\b""").containsMatchIn(t) -> PaymentMethod.CARD
            else -> fallback
        }
    }
}
