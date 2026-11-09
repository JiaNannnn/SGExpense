package com.sgexpense.capture.parsers

import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.TxType
import com.sgexpense.util.Money
import java.util.Locale

/** What a parser extracts from one notification. */
data class ParsedPayment(
    val amountCents: Long,
    val type: TxType = TxType.EXPENSE,
    val merchant: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    val account: String? = null,
    /** ShopBack "you earned $x cashback" — attaches to an earlier payment instead of creating one. */
    val cashbackOnly: Boolean = false,
)

interface NotificationParser {
    /** Returns null when the notification is not a completed payment. */
    fun parse(title: String, text: String): ParsedPayment?
}

/** Shared regexes and helpers. */
object Patterns {
    /** Amount with an explicit currency marker: S$12.50, SGD 12.50, $12.50, SGD12 */
    val AMOUNT = Regex("""(?i)(?<![A-Z])(?:S\$|SG\$|SGD|\$)\s?(${Money.NUMBER_PATTERN})""")

    private val NOISE = Regex(
        """(?i)\b(otp|one[- ]time (pass(word|code)|pin)|verification code|security code|log(ged)?[- ]?in|promo|promotion|voucher|% off|deals?|""" +
            """sale|earn up to|up to \S+ cashback|win|limited[- ]time|giveaway|statement is ready|e-?statement|bill is due|due date|""" +
            """minimum payment|reminder|requested|request to pay|is requesting|declined|unsuccessful|failed|was not successful|not processed|""" +
            """apply now|pre-approved|invite|refer)\b"""
    )
    private val INCOMING = Regex("""(?i)\b(received|credited|incoming|deposit(ed)?|has been added|top[- ]?up received|you got)\b""")
    private val REFUND = Regex("""(?i)\b(refund(ed)?|reversal|reversed)\b""")
    private val PAYNOW = Regex("""(?i)pay\s?now""")
    private val CARD_LAST4 = Regex("""(?i)(visa|mastercard|master card|amex|american express|unionpay|debit card|credit card|card)\D{0,20}?(\d{4})\b""")
    private val ACCOUNT_LAST4 = Regex("""(?i)(?:a/c|acct|account)\s*(?:no\.?\s*)?(?:ending(?: with| in)?\s*)?[x*•·.\s]*(\d{4})\b""")

    fun amounts(text: String): List<Long> = AMOUNT.findAll(text).mapNotNull { Money.parseCents(it.groupValues[1]) }.filter { it > 0 }.toList()
    fun firstAmount(text: String): Long? = amounts(text).firstOrNull()
    fun isNoise(text: String) = NOISE.containsMatchIn(text)
    fun isIncoming(text: String) = INCOMING.containsMatchIn(text)
    fun isRefund(text: String) = REFUND.containsMatchIn(text)
    fun isPayNow(text: String) = PAYNOW.containsMatchIn(text)

    /** SimplyGo fares appear on card alerts and statements as "BUS/MRT". */
    private val TRANSIT = Regex("""(?i)\bBUS\s*/\s*MRT\b|\bSIMPLY\s?GO\b|\bTRANSIT\s?LINK\b|\bEZ-?LINK\b""")
    fun isTransit(text: String) = TRANSIT.containsMatchIn(text)
    const val TRANSIT_MERCHANT = "Bus/MRT"

    fun cardLast4(text: String): Pair<String, String>? =
        CARD_LAST4.find(text)?.let { it.groupValues[1].replaceFirstChar { c -> c.uppercase() } to it.groupValues[2] }

    fun accountLast4(text: String): String? = ACCOUNT_LAST4.find(text)?.groupValues?.get(1)

    private val STOP = """(?=\s+(?:on|at|via|from|to|using|with|for|ref|reference)\b|\s+\d{1,2}[:/]\d|[.,;!(]|\s+-\s|${'$'}|\n)"""

    /** Text after a keyword like "at", "to", "from", up to the next stop word. */
    fun after(keyword: String, text: String): String? {
        val r = Regex("""(?i)\b$keyword\s+(?!your\b|you\b|a/c\b|acc|account\b|card\b|\d{1,2}[:/.]\d)([A-Za-z0-9][^\n]{1,80}?)$STOP""")
        return r.find(text)?.groupValues?.get(1)?.let { cleanMerchant(it) }?.takeIf { it.isNotBlank() }
    }

    private val TRAILING = Regex("""[\s.,:;\-*•]+$""")

    fun cleanMerchant(s: String): String =
        s.replace(Regex("""\s+"""), " ").replace(TRAILING, "").trim().take(60)

    /** Notification titles that are app names, not merchants. */
    fun isGenericTitle(title: String): Boolean {
        val t = title.lowercase(Locale.ENGLISH).trim()
        if (t.isEmpty()) return true
        if (AMOUNT.containsMatchIn(title)) return true
        val generic = listOf(
            "google wallet", "google pay", "gpay", "wallet", "payment", "payment successful", "paid", "transaction",
            "transaction alert", "uob", "uob tmrw", "tmrw", "uob mighty", "shopback", "paynow", "transfer", "alert",
            "purchase", "card transaction", "notification", "successful", "money sent", "money received", "you paid",
            "fairprice group", "fairprice pay", "scan & go", "scan and go", "e-receipt", "receipt", "order confirmed",
        )
        return generic.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") } || t.contains("transaction alert")
    }
}

/** Google Wallet tap-to-pay: title is usually the merchant, text "S$12.50 with Visa •••• 1234". */
object GoogleWalletParser : NotificationParser {
    override fun parse(title: String, text: String): ParsedPayment? {
        val all = "$title\n$text"
        if (Patterns.isNoise(all)) return null
        val amount = Patterns.firstAmount(all) ?: return null
        val merchant = when {
            !Patterns.isGenericTitle(title) -> Patterns.cleanMerchant(title)
            else -> Patterns.after("at", all) ?: Patterns.after("to", all) ?: ""
        }
        val card = Patterns.cardLast4(all)
        return ParsedPayment(
            amountCents = amount,
            type = if (Patterns.isRefund(all)) TxType.REFUND else TxType.EXPENSE,
            merchant = merchant,
            paymentMethod = PaymentMethod.GPAY_TAP,
            account = card?.let { "${it.first} •${it.second}" },
        )
    }
}

/**
 * UOB TMRW / UOB app alerts: card transactions, PayNow and FAST transfers, incoming credits.
 * e.g. "A transaction of SGD 12.50 was made with your UOB Card ending 1234 on 07/11/26 at FAIRPRICE XTRA."
 *      "You made a PayNow transfer of SGD 50.00 to JOHN TAN on your a/c ending 1234 at 7:15PM SGT"
 *      "You have received SGD 50.00 in your a/c ending 1234 via PayNow from JOHN TAN"
 */
object UobParser : NotificationParser {
    override fun parse(title: String, text: String): ParsedPayment? {
        val all = "$title\n$text"
        if (Patterns.isNoise(all)) return null
        val amount = Patterns.firstAmount(all) ?: return null
        val payNow = Patterns.isPayNow(all)
        val refund = Patterns.isRefund(all)
        val incoming = !refund && Patterns.isIncoming(all)
        val card = Patterns.cardLast4(all)
        val acct = Patterns.accountLast4(all)
        val isTransfer = payNow || Regex("""(?i)\b(transfer|fast|sent|giro)\b""").containsMatchIn(all)

        val method = when {
            payNow -> PaymentMethod.PAYNOW
            isTransfer -> PaymentMethod.BANK_TRANSFER
            card != null || Regex("""(?i)\bcard\b""").containsMatchIn(all) -> PaymentMethod.CARD
            else -> PaymentMethod.OTHER
        }
        val merchant = when {
            incoming -> Patterns.after("from", all)
            isTransfer -> Patterns.after("to", all) ?: Patterns.after("at", all)
            else -> Patterns.after("at", all) ?: Patterns.after("to", all)
        } ?: if (!Patterns.isGenericTitle(title)) Patterns.cleanMerchant(title) else ""

        val account = when {
            card != null && method == PaymentMethod.CARD -> "UOB Card •${card.second}"
            acct != null -> "UOB a/c •$acct"
            card != null -> "UOB •${card.second}"
            else -> null
        }
        return ParsedPayment(
            amountCents = amount,
            type = when { refund -> TxType.REFUND; incoming -> TxType.INCOME; else -> TxType.EXPENSE },
            merchant = merchant,
            paymentMethod = method,
            account = account,
        )
    }
}

/** ShopBack Pay payments and cashback confirmations. Promotions are ignored. */
object ShopBackParser : NotificationParser {
    private val CASHBACK = Regex("""(?i)cashback""")
    private val CASHBACK_EARNED = Regex("""(?i)\b(earned|tracked|confirmed|credited|received|you got|is here|added)\b""")
    private val PAID = Regex("""(?i)\b(paid|payment (was )?successful|payment of|you've paid|you have paid|payment completed|purchase)\b""")

    override fun parse(title: String, text: String): ParsedPayment? {
        val all = "$title\n$text"
        val amount = Patterns.firstAmount(all) ?: return null
        if (PAID.containsMatchIn(all) && !Regex("""(?i)\b(earn up to|up to)\b""").containsMatchIn(all)) {
            val merchant = Patterns.after("at", all) ?: Patterns.after("to", all)
                ?: if (!Patterns.isGenericTitle(title)) Patterns.cleanMerchant(title) else ""
            return ParsedPayment(
                amountCents = amount,
                type = if (Patterns.isRefund(all)) TxType.REFUND else TxType.EXPENSE,
                merchant = merchant,
                paymentMethod = PaymentMethod.SHOPBACK_PAY,
            )
        }
        if (CASHBACK.containsMatchIn(all) && CASHBACK_EARNED.containsMatchIn(all) && !Patterns.isNoise(all)) {
            return ParsedPayment(
                amountCents = amount,
                merchant = Patterns.after("from", all) ?: Patterns.after("at", all) ?: "",
                paymentMethod = PaymentMethod.SHOPBACK_PAY,
                cashbackOnly = true,
            )
        }
        return null
    }
}

/**
 * FairPrice Group app: FairPrice Pay / Scan & Go payments and e-receipts.
 * LinkPoints-only messages and promotions are ignored (they have no S$ amount or match the noise filter).
 */
object FairPriceParser : NotificationParser {
    private val PAID = Regex(
        """(?i)\b(paid|payment|purchase|transaction|e-?receipt|receipt|charged|spent|order (placed|confirmed))\b"""
    )
    private val PROMO = Regex("""(?i)\b(min(imum)? spend|spend \S+ (and|&) (get|earn)|off your|coupon|weekly deals?|members? (price|exclusive))\b""")

    override fun parse(title: String, text: String): ParsedPayment? {
        val all = "$title\n$text"
        if (Patterns.isNoise(all) || PROMO.containsMatchIn(all) || !PAID.containsMatchIn(all)) return null
        val amount = Patterns.firstAmount(all) ?: return null
        val merchant = Patterns.after("at", all)
            ?: if (!Patterns.isGenericTitle(title)) Patterns.cleanMerchant(title) else "FairPrice"
        val card = Patterns.cardLast4(all)
        return ParsedPayment(
            amountCents = amount,
            type = if (Patterns.isRefund(all)) TxType.REFUND else TxType.EXPENSE,
            merchant = merchant,
            paymentMethod = PaymentMethod.FAIRPRICE_PAY,
            account = card?.let { "${it.first} •${it.second}" },
        )
    }
}

/**
 * SimplyGo app (sg.com.transitlink): fare charges and EZ-Link top-ups. Bank-card fares are usually
 * charged as one daily total, so a charge here is a day's or a few trips' worth, not always one ride.
 */
object SimplyGoParser : NotificationParser {
    private val CHARGE = Regex("""(?i)\b(fare|fares|charged|deducted|paid|payment|journey|trip|top[- ]?up|topped up|transaction)\b""")
    private val TOP_UP = Regex("""(?i)\b(top[- ]?up|topped up|reload(ed)?)\b""")
    private val NOT_A_CHARGE = Regex("""(?i)\b(low balance|insufficient|balance (is|of)|card (is )?blocked|concession|expir(y|es|ing)|update your app|service (disruption|advisory))\b""")

    override fun parse(title: String, text: String): ParsedPayment? {
        val all = "$title\n$text"
        if (Patterns.isNoise(all) || NOT_A_CHARGE.containsMatchIn(all) || !CHARGE.containsMatchIn(all)) return null
        val amount = Patterns.firstAmount(all) ?: return null
        val card = Patterns.cardLast4(all)
        return ParsedPayment(
            amountCents = amount,
            type = if (Patterns.isRefund(all)) TxType.REFUND else TxType.EXPENSE,
            merchant = if (TOP_UP.containsMatchIn(all)) "EZ-Link top-up" else Patterns.TRANSIT_MERCHANT,
            paymentMethod = PaymentMethod.SIMPLYGO,
            account = card?.let { "${it.first} •${it.second}" },
        )
    }
}

/** Fallback for any other watched app: needs a currency amount and a payment verb. */
object GenericParser : NotificationParser {
    private val VERB = Regex(
        """(?i)\b(paid|payment|spent|transaction|transferred|transfer|debited|charged|purchase|sent|received|credited|refund(ed)?)\b"""
    )

    override fun parse(title: String, text: String): ParsedPayment? {
        val all = "$title\n$text"
        if (Patterns.isNoise(all) || !VERB.containsMatchIn(all)) return null
        val amount = Patterns.firstAmount(all) ?: return null
        val refund = Patterns.isRefund(all)
        val incoming = !refund && Patterns.isIncoming(all)
        val merchant = (if (incoming) Patterns.after("from", all) else Patterns.after("at", all) ?: Patterns.after("to", all))
            ?: if (!Patterns.isGenericTitle(title)) Patterns.cleanMerchant(title) else ""
        val card = Patterns.cardLast4(all)
        return ParsedPayment(
            amountCents = amount,
            type = when { refund -> TxType.REFUND; incoming -> TxType.INCOME; else -> TxType.EXPENSE },
            merchant = merchant,
            paymentMethod = when {
                Patterns.isPayNow(all) -> PaymentMethod.PAYNOW
                card != null -> PaymentMethod.CARD
                else -> PaymentMethod.OTHER
            },
            account = card?.let { "${it.first} •${it.second}" },
        )
    }
}

object ParserRegistry {
    fun parserFor(packageName: String): NotificationParser {
        val p = packageName.lowercase(Locale.ENGLISH)
        return when {
            p.contains("walletnfcrel") || p.contains("google.android.apps.wallet") || p.contains("nbu.paisa") -> GoogleWalletParser
            p.contains("uob") -> UobParser
            p.contains("shopback") -> ShopBackParser
            p.contains("fairprice") -> FairPriceParser
            p.contains("transitlink") || p.contains("simplygo") -> SimplyGoParser
            else -> GenericParser
        }
    }

    fun parse(packageName: String, title: String, text: String): ParsedPayment? {
        val parsed = parserFor(packageName).parse(title, text) ?: return null
        // A UOB / Wallet alert "at BUS/MRT SINGAPORE SG" is a SimplyGo fare.
        if (parsed.paymentMethod != PaymentMethod.SIMPLYGO && Patterns.isTransit(parsed.merchant)) {
            return parsed.copy(paymentMethod = PaymentMethod.SIMPLYGO, merchant = Patterns.TRANSIT_MERCHANT)
        }
        return parsed
    }
}
