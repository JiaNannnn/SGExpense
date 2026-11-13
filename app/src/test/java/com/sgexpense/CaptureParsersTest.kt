package com.sgexpense

import com.sgexpense.capture.Deduplicator
import com.sgexpense.capture.PayeeMatcher
import com.sgexpense.capture.QuickEntry
import com.sgexpense.capture.parsers.ParserRegistry
import com.sgexpense.capture.parsers.UobMissingAmount
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

    // --- Promotions with dollar amounts must not become expenses ---

    @Test fun fairPricePromosWithDollarsIgnored() {
        val promos = listOf(
            "Weekend deals!" to "Enjoy \$5 off when you spend \$60 on groceries. Shop now!",
            "Hot buys this week" to "Coca-Cola 24 x 320ml only \$12.95. While stocks last.",
            "Your \$10 eVoucher is waiting" to "Use it on your next purchase above \$50 before 30 Nov.",
            "FairPrice" to "Fresh durians from \$9.90/kg at selected stores. Don't miss out!",
            "Flash sale" to "1-for-1 Ice cream, now at \$6.50. Limited time only.",
            "Members price" to "Link members save up to \$20 on rice and oil this week",
            "Thank you for shopping" to "Earn 2x LinkPoints on your next \$30 purchase",
            "Gift for you" to "Get a \$5 discount on your next order with code SAVE5",
        )
        promos.forEach { (title, text) ->
            assertNull("should ignore: $title / $text", ParserRegistry.parse(fairprice, title, text))
        }
    }

    /** Real FairPrice Group notifications (Nov 2026). */
    @Test fun realFairPriceNotifications() {
        val promos = listOf(
            "Snack the hols: Up to 54% OFF! 🎅" to "🍟 2 for \$5.50 (U.P. 2 for \$6.50): Lay’s Potato Chips Holiday Edition\n🍫 \$15.50 (U.P. \$20.50): Lindt Lindor Assorted 200g\nFind more festive goodies >",
            "5-Course Lunch & Wine Pairing! 🎁" to "Feast on Delicato Roast Pork, Wine & Log Cakes plus enjoy a \$10 Return Voucher to spend on the same day! Sign up now >",
            "Up to 50% OFF ends TODAY! 🚨" to "🍊 5 for \$3.95 (U.P. 5 for \$6.50): Vitor Australia Navel Oranges\n🧻 \$17.95 (U.P. \$24.96): Kleenex Ultrasoft 4/3-Ply Bathroom Tissue 30s\nDon’t miss out! 👆",
            "✈️ Traveling soon? Skip the FX fees" to "Enjoy 0% foreign transaction fees. Sign up for a Trust credit card and get up to S\$8K. T&Cs apply.",
            "Going fast: Mofusand Collection ⏳" to "Don’t miss out on limited-edition collectibles perfect for your year-end vacation! 🧳 Tap to find out how to redeem >",
        )
        promos.forEach { (title, text) -> assertNull("should ignore: $title", ParserRegistry.parse(fairprice, title, text)) }

        val paid = ParserRegistry.parse(fairprice, "Payment successful", "FairPrice Jurong Point: \$3.65")!!
        assertEquals(365L, paid.amountCents)
        assertEquals("FairPrice Jurong Point", paid.merchant)
        assertEquals(PaymentMethod.FAIRPRICE_PAY, paid.paymentMethod)
    }

    /** Real UOB alerts (Nov 2026), names replaced. UOB masks parts of names with X. */
    @Test fun realUobTransferAlertsWithoutAmount() {
        val payNow = UobMissingAmount.hint(
            "UOB Personal Internet Banking Notification Alerts",
            "UOB - Your PayNow transfer to ABXX CXX DEX on 12-Nov-2026 is successful.",
        )!!
        assertEquals(PaymentMethod.PAYNOW, payNow.paymentMethod)
        assertEquals("ABXX CXX DEX", payNow.merchant)
        assertEquals(TxType.EXPENSE, payNow.type)

        val shop = UobMissingAmount.hint("UOB TMRW", "UOB - Your PayNow transfer to HAPPY BBQ GROUP (B on 10-Nov-2026 is successful.")!!
        assertEquals("HAPPY BBQ GROUP", shop.merchant)

        val fast = UobMissingAmount.hint("UOB-FAST Funds Transfer Status", "UOB-Your FAST funds transfer to TAN AH KOW on 10-Nov-2026 is successful")!!
        assertEquals(PaymentMethod.BANK_TRANSFER, fast.paymentMethod)
        assertEquals("TAN AH KOW", fast.merchant)

        // The detailed alert does have an amount and is parsed normally (same method as the FAST prompt).
        val detailed = ParserRegistry.parse(
            uob, "UOB TMRW",
            "You made/scheduled a funds transfer(s) of SGD 500.00 to STANDARD CHARTERED ( a/c ending 1111 from your a/c ending 2222 at 9:23PM SGT, 10 Nov 26. If unauthorised, call UOB 24/7 Fraud Hotline.",
        )!!
        assertEquals(50000L, detailed.amountCents)
        assertEquals(PaymentMethod.BANK_TRANSFER, detailed.paymentMethod)
    }

    @Test fun promosIgnoredInOtherApps() {
        assertNull(ParserRegistry.parse(uob, "UOB Rewards", "Enjoy up to 10% cashback, min spend \$500. Apply now!"))
        assertNull(ParserRegistry.parse(shopback, "Big sale", "Get \$5 off your first purchase with ShopBack Pay"))
        assertNull(ParserRegistry.parse(wallet, "Google Wallet", "Get \$5 off at Starbucks when you pay with Google Pay"))
    }

    @Test fun realPaymentsAtPromoSoundingMerchantsStillCaptured() {
        val card = ParserRegistry.parse(uob, "Transaction Alert", "A transaction of SGD 6.90 was made with your UOB Card ending 1234 at FLASH COFFEE.")!!
        assertEquals(690L, card.amountCents)
        val tap = ParserRegistry.parse(wallet, "Flash Coffee", "S$6.90 with Visa •••• 1234")!!
        assertEquals("Flash Coffee", tap.merchant)
        val sale = ParserRegistry.parse(uob, "Transaction Alert", "A transaction of SGD 20.00 was made with your UOB Card ending 1234 at THE SALE SHOP.")!!
        assertEquals(2000L, sale.amountCents)
    }

    // --- UOB alerts without an amount ---

    @Test fun uobPayNowWithoutAmountGivesHint() {
        assertNull(ParserRegistry.parse(uob, "PayNow", "You have sent money to JOHN TAN via PayNow."))
        val h = UobMissingAmount.hint("PayNow", "You have sent money to JOHN TAN via PayNow.")!!
        assertEquals(PaymentMethod.PAYNOW, h.paymentMethod)
        assertEquals(TxType.EXPENSE, h.type)
        assertEquals("JOHN TAN", h.merchant)
        assertEquals("UOB PayNow transfer", h.label)
    }

    @Test fun uobPayNowReceivedWithoutAmount() {
        val h = UobMissingAmount.hint("UOB TMRW", "You have received a PayNow transfer from MARY LIM in your a/c ending 5678")!!
        assertEquals(TxType.INCOME, h.type)
        assertEquals("MARY LIM", h.merchant)
        assertEquals("UOB a/c •5678", h.account)
        assertEquals("UOB PayNow received", h.label)
    }

    @Test fun uobBareTransferAlert() {
        val h = UobMissingAmount.hint("Transfer successful", "Your PayNow transfer was successful.")!!
        assertEquals(PaymentMethod.PAYNOW, h.paymentMethod)
        assertEquals("", h.merchant)
    }

    @Test fun uobNonTransactionsGiveNoHint() {
        assertNull(UobMissingAmount.hint("UOB TMRW", "Your PayNow daily transfer limit has been updated."))
        assertNull(UobMissingAmount.hint("UOB TMRW", "Your OTP for PayNow transfer is 123456."))
        assertNull(UobMissingAmount.hint("Stay safe", "Scam tips: never share your PIN when making a transfer."))
        assertNull(UobMissingAmount.hint("UOB TMRW", "Log in to see your new dashboard."))
        // Has an amount: handled by the normal parser instead
        assertNull(UobMissingAmount.hint("PayNow", "You made a PayNow transfer of SGD 50.00 to JOHN TAN"))
    }

    @Test fun payeeMatching() {
        assertTrue(PayeeMatcher.samePayee("HWANG WEI MING", "HWAXX WEX MIXX"))
        assertTrue(PayeeMatcher.samePayee("HAPPY BBQ GROUP", "HAPPY BBQ GROUP"))
        assertTrue(PayeeMatcher.samePayee("", "KOPI CORNER"))
        assertTrue(!PayeeMatcher.samePayee("HAPPY BBQ GROUP", "KOPI CORNER"))
        assertTrue(!PayeeMatcher.samePayee("STANDARD CHARTERED", "TAN AH KOW"))
    }

    @Test fun quickEntryParsing() {
        assertEquals(QuickEntry.Entry(1250, "Koufu"), QuickEntry.parse("12.50 Koufu"))
        assertEquals(QuickEntry.Entry(800, "grab"), QuickEntry.parse("\$8 grab"))
        assertEquals(QuickEntry.Entry(450, "Koufu"), QuickEntry.parse("Koufu 4.5"))
        assertEquals(QuickEntry.Entry(520, "7-Eleven"), QuickEntry.parse("7-Eleven 5.20"))
        assertEquals(QuickEntry.Entry(123450, "rent"), QuickEntry.parse("S$1,234.50 rent"))
        assertEquals(QuickEntry.Entry(3000, ""), QuickEntry.parse("30"))
        assertNull(QuickEntry.parse("lunch"))
        assertNull(QuickEntry.parse("0"))
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
