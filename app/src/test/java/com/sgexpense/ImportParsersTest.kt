package com.sgexpense

import com.sgexpense.backup.XlsxWriter
import com.sgexpense.data.model.MerchantRule
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.TxType
import com.sgexpense.data.repository.Categorizer
import com.sgexpense.importer.ocr.OcrAnalyzer
import com.sgexpense.importer.ocr.OcrLine
import com.sgexpense.importer.ocr.ReceiptParser
import com.sgexpense.importer.ocr.RowGrouper
import com.sgexpense.importer.ocr.ScreenshotListParser
import com.sgexpense.importer.sheet.CsvReader
import com.sgexpense.importer.sheet.HtmlTableReader
import com.sgexpense.importer.sheet.SheetReader
import com.sgexpense.importer.sheet.StatementMapper
import com.sgexpense.util.DateParsing
import com.sgexpense.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime

class ImportParsersTest {
    private val today = LocalDate.of(2026, 11, 7)

    @Test fun money() {
        assertEquals(123450L, Money.parseSignedCents("S$1,234.50"))
        assertEquals(1250L, Money.parseSignedCents("SGD 12.5"))
        assertEquals(-1200L, Money.parseSignedCents("(12.00)"))
        assertEquals(-1200L, Money.parseSignedCents("12.00 CR"))
        assertEquals(-850L, Money.parseSignedCents("-8.50"))
        assertEquals(null, Money.parseSignedCents("abc"))
        assertEquals("S$1,234.50", Money.format(123450))
        assertEquals("-S$8.50", Money.format(-850))
    }

    @Test fun dates() {
        assertEquals(LocalDate.of(2026, 11, 7), DateParsing.findDate("07/11/2026", today))
        assertEquals(LocalDate.of(2026, 11, 7), DateParsing.findDate("on 07/11/26 at", today))
        assertEquals(LocalDate.of(2026, 11, 7), DateParsing.findDate("7 Nov 2026, 7:15 PM", today))
        assertEquals(LocalDate.of(2026, 10, 30), DateParsing.findDate("30 Oct", today))
        assertEquals(LocalDate.of(2025, 12, 24), DateParsing.findDate("24 Dec", today)) // future → last year
        assertEquals(LocalDate.of(2026, 11, 6), DateParsing.findDate("Yesterday", today))
        assertEquals(LocalDate.of(2026, 3, 1), DateParsing.findDate("2026-03-01", today))
        assertEquals(LocalTime.of(19, 15), DateParsing.findTime("7:15PM SGT"))
    }

    @Test fun rowGrouperJoinsSameLine() {
        val rows = RowGrouper.group(listOf(
            OcrLine("12.50", 400, 302, 460, 322),
            OcrLine("TOTAL", 10, 300, 80, 320),
            OcrLine("Thank you", 10, 360, 120, 380),
        ))
        assertEquals(listOf("TOTAL  12.50", "Thank you"), rows)
    }

    @Test fun receipt() {
        val rows = listOf(
            "KOUFU PTE LTD",
            "Jurong Point #01-23",
            "GST Reg No: 200012345K",
            "Date: 07/11/2026  12:31",
            "Chicken Rice  4.50",
            "Kopi O  1.40",
            "Subtotal  5.90",
            "GST 9%  0.53",
            "TOTAL (INCL GST)  6.43",
            "CASH  10.00",
            "CHANGE  3.57",
        )
        assertTrue(ReceiptParser.looksLikeReceipt(rows))
        val d = ReceiptParser.parse(rows, today)!!
        assertEquals(643L, d.amountCents)
        assertEquals(LocalDate.of(2026, 11, 7), d.date)
        assertEquals(LocalTime.of(12, 31), d.time)
        assertEquals("KOUFU PTE LTD", d.merchant)
    }

    @Test fun receiptTotalOnNextRow() {
        val rows = listOf("DON DON DONKI", "Tax Invoice", "Item A  3.90", "Item B  5.00", "Grand Total", "S$8.90")
        assertEquals(890L, ReceiptParser.parse(rows, today)!!.amountCents)
    }

    @Test fun screenshotList() {
        val rows = listOf(
            "Transaction History",
            "06 Nov 2026",
            "FairPrice Xtra  -S$23.80",
            "Grab  -S$8.40",
            "05 Nov 2026",
            "MARY LIM  +S$120.00",
            "PayNow",
        )
        val out = ScreenshotListParser.parse(rows, today)
        assertEquals(3, out.size)
        assertEquals("FairPrice Xtra", out[0].merchant)
        assertEquals(2380L, out[0].amountCents)
        assertEquals(LocalDate.of(2026, 11, 6), out[0].date)
        assertEquals(LocalDate.of(2026, 11, 6), out[1].date)
        assertEquals(TxType.INCOME, out[2].type)
        assertEquals(LocalDate.of(2026, 11, 5), out[2].date)
    }

    @Test fun payNowConfirmationScreenshot() {
        val rows = listOf("Transfer successful", "SGD 50.00", "To", "JOHN TAN", "PayNow", "7 Nov 2026, 7:15 PM", "Reference 12345")
        val out = OcrAnalyzer.analyze(rows, today)
        assertEquals(1, out.size)
        assertEquals(5000L, out[0].amountCents)
        assertEquals("JOHN TAN", out[0].merchant)
        assertEquals(PaymentMethod.PAYNOW, out[0].paymentMethod)
        assertEquals(TxType.EXPENSE, out[0].type)
    }

    @Test fun uobAccountCsv() {
        val csv = """
            Account Transaction History
            Account Number:,123-456-789-0
            Statement Period:,01 Nov 2026 To 07 Nov 2026

            Transaction Date,Transaction Description,Withdrawal,Deposit,Available Balance
            07 Nov 2026,"PAYNOW-FAST OTHR JOHN TAN",50.00,,"1,950.00"
            06 Nov 2026,NETS QR FAIRPRICE XTRA,23.80,,"2,000.00"
            05 Nov 2026,GIRO SALARY ACME PTE LTD,,"3,000.00","2,023.80"
            Total,,73.80,3000.00,
        """.trimIndent()
        val sheet = CsvReader.read(csv)
        val m = StatementMapper.detect(sheet)!!
        val out = StatementMapper.map(sheet, m, today)
        assertEquals(3, out.size)
        assertEquals(5000L, out[0].amountCents)
        assertEquals(TxType.EXPENSE, out[0].type)
        assertEquals(PaymentMethod.PAYNOW, out[0].paymentMethod)
        assertEquals("JOHN TAN", out[0].merchant)
        assertEquals("FAIRPRICE XTRA", out[1].merchant)
        assertEquals(TxType.INCOME, out[2].type)
        assertEquals(300000L, out[2].amountCents)
    }

    @Test fun uobCardStatementHtmlXls() {
        val html = """<html><body><table>
            <tr><td>Credit Card Transaction History</td></tr>
            <tr><th>Transaction Date</th><th>Posting Date</th><th>Description</th><th>Transaction Amount(Local)</th></tr>
            <tr><td>03 Nov 2026</td><td>04 Nov 2026</td><td>SHOPEE SINGAPORE SG</td><td>45.60</td></tr>
            <tr><td>02 Nov 2026</td><td>03 Nov 2026</td><td>REFUND SHOPEE</td><td>10.00 CR</td></tr>
            </table></body></html>"""
        val sheet = SheetReader.read(html.toByteArray())
        val out = StatementMapper.map(sheet, StatementMapper.detect(sheet)!!, today)
        assertEquals(2, out.size)
        assertEquals(4560L, out[0].amountCents)
        assertEquals(PaymentMethod.CARD, out[0].paymentMethod)
        assertEquals(TxType.REFUND, out[1].type)
        assertEquals(1, HtmlTableReader.read(html).rows[0].size)
    }

    @Test fun xlsxRoundTrip() {
        val bytes = ByteArrayOutputStream().also {
            XlsxWriter.write(it, "S", listOf(
                listOf("Date", "Description", "Amount"),
                listOf("2026-11-01", "GRAB RIDES", -8.4),
                listOf("2026-11-02", "SALARY", 3000.0),
            ))
        }.toByteArray()
        val sheet = SheetReader.read(bytes)
        assertEquals("GRAB RIDES", sheet.cell(1, 1))
        val m = StatementMapper.detect(sheet)!!
        assertEquals(false, m.positiveIsExpense) // signed bank column: negatives are spending
        val out = StatementMapper.map(sheet, m, today)
        assertEquals(2, out.size)
        assertEquals(840L, out[0].amountCents)
        assertEquals(TxType.EXPENSE, out[0].type)
        assertEquals(TxType.INCOME, out[1].type)
        assertEquals(LocalDate.of(2026, 11, 1), out[0].date)
    }

    @Test fun busMrtStatementLine() {
        val csv = """
            Transaction Date,Posting Date,Description,Transaction Amount(Local)
            08 Nov 2026,09 Nov 2026,BUS/MRT 123456789 SINGAPORE SG,3.71
            07 Nov 2026,08 Nov 2026,BUSINESS TIMES SUBSCRIPTION,12.00
        """.trimIndent()
        val sheet = CsvReader.read(csv)
        val out = StatementMapper.map(sheet, StatementMapper.detect(sheet)!!, today)
        assertEquals(PaymentMethod.SIMPLYGO, out[0].paymentMethod)
        assertEquals(PaymentMethod.CARD, out[1].paymentMethod)
    }

    @Test fun simplyGoJourneyScreenshot() {
        val rows = listOf("SimplyGo", "Journey History", "08 Nov 2026", "Bus 190  $1.19", "Orchard Stn → Bt Timah Rd", "MRT  $1.62", "Jurong East → Buona Vista")
        val out = OcrAnalyzer.analyze(rows, today)
        assertEquals(2, out.size)
        assertTrue(out.all { it.paymentMethod == PaymentMethod.SIMPLYGO })
        assertEquals(119L, out[0].amountCents)
        assertEquals(LocalDate.of(2026, 11, 8), out[1].date)
    }

    @Test fun shortKeywordsMatchWholeWordsOnly() {
        val rules = listOf(MerchantRule(1, "BUS", 3), MerchantRule(2, "MRT", 3), MerchantRule(3, "BUS/MRT", 3))
        assertEquals(3L, Categorizer.match("BUS/MRT 123456789 SINGAPORE", rules)?.categoryId)
        assertEquals(3L, Categorizer.match("Bus 190", rules)?.categoryId)
        assertEquals(null, Categorizer.match("BUSINESS TIMES", rules))
        assertEquals(null, Categorizer.match("SMRT TAXIS", rules.take(2)))
    }

    @Test fun categorizer() {
        val rules = listOf(
            MerchantRule(1, "GRAB", 3), MerchantRule(2, "GRABFOOD", 1), MerchantRule(3, "FAIRPRICE", 2),
            MerchantRule(4, "KOUFU", 9, learned = true),
        )
        assertEquals(3L, Categorizer.match("GRAB*TAXI 12345", rules)?.categoryId)
        assertEquals(1L, Categorizer.match("GRABFOOD SINGAPORE", rules)?.categoryId)
        assertEquals(2L, Categorizer.match("NETS QR NTUC FAIRPRICE XTRA", rules)?.categoryId)
        assertEquals(9L, Categorizer.match("Koufu @ JP", rules)?.categoryId)
        assertEquals("FAIRPRICE XTRA", Categorizer.learnKey("PAYNOW-FAST FairPrice Xtra Singapore SG"))
        assertNotNull(Categorizer.normalize("x"))
    }
}
