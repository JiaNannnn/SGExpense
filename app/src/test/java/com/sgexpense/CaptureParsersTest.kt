package com.sgexpense

import com.sgexpense.capture.Deduplicator
import com.sgexpense.capture.parsers.ParserRegistry
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.model.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureParsersTest {
    private val wallet = "com.google.android.apps.walletnfcrel"
    private val uob = "com.uob.mighty.app"
    private val shopback = "com.shopback.app"

    @Test fun googleWalletTap() {
        val p = ParserRegistry.parse(wallet, "FairPrice Xtra", "S$12.50 with Visa •••• 1234")!!
        assertEquals(1250L, p.amountCents)
        assertEquals("FairPrice Xtra", p.merchant)
        assertEquals(PaymentMethod.GPAY_TAP, p.paymentMethod)
        assertEquals("Visa •1234", p.account)
        assertEquals(TxType.EXPENSE, p.type)
    }

    @Test fun googleWalletGenericTitle() {
        val p = ParserRegistry.parse(wallet, "Google Wallet", "You paid $8.40 at GRAB*TAXI with Mastercard ••5678")!!
        assertEquals(840L, p.amountCents)
        assertEquals("GRAB*TAXI", p.merchant)
    }

    @Test fun walletNonPaymentIgnored() {
        assertNull(ParserRegistry.parse(wallet, "Your card is ready", "Visa •••• 1234 is ready to use for tap to pay"))
    }

    @Test fun uobCardAlert() {
        val p = ParserRegistry.parse(
            uob, "Transaction Alert",
            "A transaction of SGD 23.80 was made with your UOB Card ending 1234 on 07/11/26 at NTUC FAIRPRICE XTRA. If unauthorised, call 24/7 Fraud Hotline now",
        )!!
        assertEquals(2380L, p.amountCents)
        assertEquals("NTUC FAIRPRICE XTRA", p.merchant)
        assertEquals(PaymentMethod.CARD, p.paymentMethod)
        assertEquals("UOB Card •1234", p.account)
    }

    @Test fun uobPayNowOutgoing() {
        val p = ParserRegistry.parse(
            uob, "PayNow",
            "You made a PayNow transfer of SGD 50.00 to JOHN TAN on your a/c ending 5678 at 7:15PM SGT, 07 Nov 26.",
        )!!
        assertEquals(5000L, p.amountCents)
        assertEquals("JOHN TAN", p.merchant)
        assertEquals(PaymentMethod.PAYNOW, p.paymentMethod)
        assertEquals(TxType.EXPENSE, p.type)
        assertEquals("UOB a/c •5678", p.account)
    }

    @Test fun uobPayNowIncoming() {
        val p = ParserRegistry.parse(
            uob, "Money received",
            "You have received SGD 120.00 in your a/c ending 5678 via PayNow from MARY LIM on 07 Nov 26.",
        )!!
        assertEquals(12000L, p.amountCents)
        assertEquals(TxType.INCOME, p.type)
        assertEquals("MARY LIM", p.merchant)
        assertEquals(PaymentMethod.PAYNOW, p.paymentMethod)
    }

    @Test fun otpAndPromoIgnored() {
        assertNull(ParserRegistry.parse(uob, "UOB", "Your OTP for the transaction of SGD 99.00 at SHOPEE is 123456. Do not share."))
        assertNull(ParserRegistry.parse(shopback, "Flash deal!", "Earn up to 20% cashback at Lazada today. Min spend $30"))
        assertNull(ParserRegistry.parse(uob, "PayNow request", "JOHN TAN is requesting SGD 20.00 from you via PayNow"))
    }

    @Test fun shopBackPayment() {
        val p = ParserRegistry.parse(shopback, "Payment successful", "You've paid $15.90 at Koufu with ShopBack Pay.")!!
        assertEquals(1590L, p.amountCents)
        assertEquals("Koufu", p.merchant)
        assertEquals(PaymentMethod.SHOPBACK_PAY, p.paymentMethod)
    }

    @Test fun shopBackCashback() {
        val p = ParserRegistry.parse(shopback, "Cashback tracked!", "You earned $1.20 Cashback from Koufu.")!!
        assertTrue(p.cashbackOnly)
        assertEquals(120L, p.amountCents)
    }

    private val fairprice = "com.fairprice.mcomapp"

    @Test fun fairPricePay() {
        val p = ParserRegistry.parse(fairprice, "Payment successful", "You paid $45.30 at FairPrice Xtra Jem with FairPrice Pay.")!!
        assertEquals(4530L, p.amountCents)
        assertEquals("FairPrice Xtra Jem", p.merchant)
        assertEquals(PaymentMethod.FAIRPRICE_PAY, p.paymentMethod)
    }

    @Test fun fairPriceReceiptWithoutStoreName() {
        val p = ParserRegistry.parse(fairprice, "FairPrice Group", "Your e-receipt for S$12.90 is ready")!!
        assertEquals(1290L, p.amountCents)
        assertEquals("FairPrice", p.merchant)
    }

    @Test fun fairPricePromosAndLinkPointsIgnored() {
        assertNull(ParserRegistry.parse(fairprice, "Weekly deals", "Get $5 off your next purchase with min spend $60!"))
        assertNull(ParserRegistry.parse(fairprice, "LinkPoints earned", "You've earned 45 LinkPoints from your last transaction"))
    }

    @Test fun fairPriceMergesWithUobCardAlert() {
        val fp = ParserRegistry.parse(fairprice, "Payment successful", "You paid $45.30 at FairPrice Xtra Jem with FairPrice Pay.")!!
        val fpTx = Transaction(
            id = 3, amountCents = fp.amountCents, merchant = fp.merchant, paymentMethod = fp.paymentMethod,
            occurredAt = 0, source = Source.NOTIFICATION, sourceApp = fairprice,
        )
        val bank = ParserRegistry.parse(uob, "Transaction Alert", "A transaction of SGD 45.30 was made with your UOB Card ending 1234 at NTUC FAIRPRICE JEM.")!!
        val match = Deduplicator.findMatch(bank, 30_000, uob, listOf(fpTx))!!
        assertEquals(PaymentMethod.FAIRPRICE_PAY, Deduplicator.merge(match, bank, "raw").paymentMethod)
    }

    private val simplygo = "sg.com.transitlink"

    @Test fun uobAlertForSimplyGoFare() {
        val p = ParserRegistry.parse(uob, "Transaction Alert", "A transaction of SGD 3.71 was made with your UOB Card ending 1234 on 08/11/26 at BUS/MRT SINGAPORE SG.")!!
        assertEquals(371L, p.amountCents)
        assertEquals(PaymentMethod.SIMPLYGO, p.paymentMethod)
        assertEquals("Bus/MRT", p.merchant)
        assertEquals("UOB Card •1234", p.account)
    }

    @Test fun walletTapAtBusMrt() {
        val p = ParserRegistry.parse(wallet, "BUS/MRT", "S$2.19 with Visa •••• 1234")!!
        assertEquals(PaymentMethod.SIMPLYGO, p.paymentMethod)
    }

    @Test fun simplyGoAppNotifications() {
        val fare = ParserRegistry.parse(simplygo, "SimplyGo", "Fare of $4.25 has been charged to your Visa card ending 1234 for your journeys on 8 Nov")!!
        assertEquals(425L, fare.amountCents)
        assertEquals(PaymentMethod.SIMPLYGO, fare.paymentMethod)
        assertEquals("Bus/MRT", fare.merchant)
        val topUp = ParserRegistry.parse(simplygo, "Top-up successful", "Your EZ-Link card has been topped up with $20.00")!!
        assertEquals("EZ-Link top-up", topUp.merchant)
        assertNull(ParserRegistry.parse(simplygo, "Low balance", "Your card balance is $1.20. Top up now to continue your journey."))
        assertNull(ParserRegistry.parse(simplygo, "Service advisory", "Service disruption on the NSL. Free bus rides available."))
    }

    @Test fun genericBankApp() {
        val p = ParserRegistry.parse("com.example.bank", "Card used", "SGD 5.60 spent at TOAST BOX with card ending 9999")!!
        assertEquals(560L, p.amountCents)
        assertEquals("TOAST BOX", p.merchant)
    }

    @Test fun dedupMergesWalletAndBankAlert() {
        val walletTx = Transaction(
            id = 7, amountCents = 2380, merchant = "FairPrice", paymentMethod = PaymentMethod.GPAY_TAP,
            occurredAt = 1_000_000L, source = Source.NOTIFICATION, sourceApp = wallet,
        )
        val bank = ParserRegistry.parse(
            uob, "Transaction Alert",
            "A transaction of SGD 23.80 was made with your UOB Card ending 1234 on 07/11/26 at NTUC FAIRPRICE XTRA.",
        )!!
        val match = Deduplicator.findMatch(bank, 1_000_000L + 60_000, uob, listOf(walletTx))
        assertNotNull(match)
        val merged = Deduplicator.merge(match!!, bank, "raw")
        assertEquals(PaymentMethod.GPAY_TAP, merged.paymentMethod)
        assertEquals("NTUC FAIRPRICE XTRA", merged.merchant)
        assertEquals("UOB Card •1234", merged.account)
    }

    @Test fun dedupKeepsSameAppAndFarApart() {
        val tx = Transaction(id = 1, amountCents = 500, occurredAt = 0, source = Source.NOTIFICATION, sourceApp = uob)
        val p = ParserRegistry.parse(uob, "PayNow", "You made a PayNow transfer of SGD 5.00 to ALEX on your a/c ending 5678")!!
        // Same app: two real transfers of the same amount
        assertNull(Deduplicator.findMatch(p, 60_000, uob, listOf(tx)))
        // Different app, but 20 minutes apart
        assertNull(Deduplicator.findMatch(p, 20 * 60_000, wallet, listOf(tx)))
    }
}
