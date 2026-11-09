package com.sgexpense.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date parsing for Singapore formats (day first). */
object DateParsing {
    private const val MONTHS =
        "january|february|march|april|june|july|august|september|october|november|december|" +
            "jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec"

    private val ISO = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val NUMERIC = Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4}|\d{2})\b""")
    private val DAY_MON_YEAR = Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?[\s\-]+($MONTHS)\.?,?[\s\-]+(\d{4}|\d{2})\b""")
    private val MON_DAY_YEAR = Regex("""(?i)\b($MONTHS)\.?\s+(\d{1,2}),?\s+(\d{4})\b""")
    private val DAY_MON = Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?\s+($MONTHS)\b""")
    private val MON_DAY = Regex("""(?i)\b($MONTHS)\s+(\d{1,2})\b""")
    private val TODAY = Regex("""(?i)\btoday\b""")
    private val YESTERDAY = Regex("""(?i)\byesterday\b""")
    private val TIME = Regex("""(?i)\b(\d{1,2}):(\d{2})(?::\d{2})?\s*(am|pm)?\b""")

    private fun month(name: String): Int? {
        val n = name.lowercase(Locale.ENGLISH).take(3)
        return Month.entries.firstOrNull { it.name.lowercase(Locale.ENGLISH).startsWith(n) }?.value
    }

    private fun year(y: String): Int = if (y.length == 2) 2000 + y.toInt() else y.toInt()

    private fun safe(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (e: Exception) { null }

    /** Year-less dates go in the current year, or last year if that would be in the future. */
    private fun noYear(m: Int, d: Int, today: LocalDate): LocalDate? {
        val candidate = safe(today.year, m, d) ?: return null
        return if (candidate.isAfter(today.plusDays(1))) candidate.minusYears(1) else candidate
    }

    /** Finds the first date in free text (OCR rows, cells, notification text). */
    fun findDate(text: String, today: LocalDate = todaySg()): LocalDate? {
        ISO.find(text)?.let { m ->
            safe(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it }
        }
        NUMERIC.find(text)?.let { m ->
            val (d, mo, y) = m.destructured
            safe(year(y), mo.toInt(), d.toInt())?.let { return it }
        }
        DAY_MON_YEAR.find(text)?.let { m ->
            val mo = month(m.groupValues[2])
            if (mo != null) safe(year(m.groupValues[3]), mo, m.groupValues[1].toInt())?.let { return it }
        }
        MON_DAY_YEAR.find(text)?.let { m ->
            val mo = month(m.groupValues[1])
            if (mo != null) safe(m.groupValues[3].toInt(), mo, m.groupValues[2].toInt())?.let { return it }
        }
        DAY_MON.find(text)?.let { m ->
            val mo = month(m.groupValues[2])
            if (mo != null) noYear(mo, m.groupValues[1].toInt(), today)?.let { return it }
        }
        MON_DAY.find(text)?.let { m ->
            val mo = month(m.groupValues[1])
            if (mo != null) noYear(mo, m.groupValues[2].toInt(), today)?.let { return it }
        }
        if (TODAY.containsMatchIn(text)) return today
        if (YESTERDAY.containsMatchIn(text)) return today.minusDays(1)
        return null
    }

    /** Removes the first date found (used to clean descriptions). */
    fun stripDates(text: String): String {
        var t = text
        for (r in listOf(ISO, NUMERIC, DAY_MON_YEAR, MON_DAY_YEAR, DAY_MON, MON_DAY, TODAY, YESTERDAY, TIME)) {
            t = t.replace(r, " ")
        }
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    fun findTime(text: String): LocalTime? {
        val m = TIME.find(text) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val ampm = m.groupValues[3].lowercase(Locale.ENGLISH)
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        if (h > 23 || min > 59) return null
        return LocalTime.of(h, min)
    }

    /** Excel serial date (1900 system) to LocalDate. */
    fun fromExcelSerial(serial: Double): LocalDate = LocalDate.of(1899, 12, 30).plusDays(serial.toLong())

    val displayDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    val displayDay: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    val displayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    val displayTime: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    val fileStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ENGLISH)
}
