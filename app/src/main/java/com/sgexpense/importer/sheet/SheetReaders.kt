package com.sgexpense.importer.sheet

import com.sgexpense.util.DateParsing
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** A sheet as rows of cell strings. Date cells are written as ISO yyyy-MM-dd. */
data class SheetData(val rows: List<List<String>>) {
    val width get() = rows.maxOfOrNull { it.size } ?: 0
    fun cell(r: Int, c: Int): String = rows.getOrNull(r)?.getOrNull(c)?.trim().orEmpty()
}

object SheetReader {
    /** Sniffs the content (not the file name) because bank "xls" files are sometimes HTML or CSV. */
    fun read(bytes: ByteArray): SheetData {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return XlsxReader.read(bytes)
        if (bytes.size >= 8 && (bytes[0].toInt() and 0xFF) == 0xD0 && (bytes[1].toInt() and 0xFF) == 0xCF) return XlsReader.read(bytes)
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t').lowercase()
        if (head.startsWith("<") && (head.contains("<table") || head.contains("<html") || head.contains("<tr"))) {
            return HtmlTableReader.read(String(bytes, Charsets.UTF_8))
        }
        return CsvReader.read(decode(bytes))
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('�')) String(bytes, Charset.forName("windows-1252")) else utf8
    }
}

object CsvReader {
    fun read(text: String): SheetData {
        val body = text.removePrefix("﻿")
        val firstLines = body.lineSequence().take(10).joinToString("\n")
        val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLines.count { it == d } }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                inQuotes && c == '"' && body.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> { row.add(cell.toString()); cell.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && body.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return SheetData(rows.map { r -> r.map { it.trim() } })
    }
}

object HtmlTableReader {
    private val TR = Regex("""(?is)<tr[^>]*>(.*?)</tr>""")
    private val TD = Regex("""(?is)<t[dh][^>]*>(.*?)</t[dh]>""")
    private val TAG = Regex("""(?s)<[^>]+>""")

    fun read(html: String): SheetData = SheetData(
        TR.findAll(html).map { tr ->
            TD.findAll(tr.groupValues[1]).map { unescape(TAG.replace(it.groupValues[1], " ")) }.toList()
        }.toList()
    )

    private fun unescape(s: String) = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("""\s+"""), " ").trim()
}

/** Legacy BIFF .xls via JExcelApi. */
object XlsReader {
    fun read(bytes: ByteArray): SheetData {
        val settings = jxl.WorkbookSettings().apply {
            encoding = "Cp1252"
            setSuppressWarnings(true)
            setGCDisabled(true)
        }
        val wb = jxl.Workbook.getWorkbook(ByteArrayInputStream(bytes), settings)
        try {
            val sheet = wb.getSheet(0)
            val rows = (0 until sheet.rows).map { r ->
                sheet.getRow(r).map { c ->
                    when (c) {
                        is jxl.DateCell -> c.date.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString()
                        is jxl.NumberCell -> plainNumber(c.value)
                        else -> c.contents.orEmpty()
                    }
                }
            }
            return SheetData(rows)
        } finally {
            wb.close()
        }
    }
}

/** Minimal .xlsx reader: first worksheet, shared strings, date-formatted cells. */
object XlsxReader {
    private val BUILTIN_DATE_FORMATS = (14..22).toSet() + setOf(27, 30, 36, 45, 46, 47, 50, 57)

    fun read(bytes: ByteArray): SheetData {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val name = e.name.trimStart('/')
                if (name.startsWith("xl/") && name.endsWith(".xml")) entries[name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        val shared = entries["xl/sharedStrings.xml"]?.let(::sharedStrings) ?: emptyList()
        val dateStyles = entries["xl/styles.xml"]?.let(::dateStyleIndexes) ?: emptySet()
        val sheetName = firstSheetPath(entries) ?: return SheetData(emptyList())
        return parseSheet(entries.getValue(sheetName), shared, dateStyles)
    }

    private fun doc(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        .newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun Element.children(tag: String): List<Element> {
        val nl = getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    private fun firstSheetPath(entries: Map<String, ByteArray>): String? {
        val wb = entries["xl/workbook.xml"]
        val rels = entries["xl/_rels/workbook.xml.rels"]
        if (wb != null && rels != null) {
            val firstSheet = doc(wb).documentElement.children("sheet").firstOrNull()
            val rid = firstSheet?.getAttribute("r:id")
            val target = doc(rels).documentElement.children("Relationship").firstOrNull { it.getAttribute("Id") == rid }?.getAttribute("Target")
            if (target != null) {
                val path = if (target.startsWith("/")) target.trimStart('/') else "xl/$target"
                if (entries.containsKey(path)) return path
            }
        }
        return entries.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()
    }

    private fun sharedStrings(bytes: ByteArray): List<String> =
        doc(bytes).documentElement.children("si").map { si -> si.children("t").joinToString("") { it.textContent } }

    private fun dateStyleIndexes(bytes: ByteArray): Set<Int> {
        val root = doc(bytes).documentElement
        val customDate = root.children("numFmt").filter {
            val code = it.getAttribute("formatCode").lowercase().replace(Regex("""\[[^\]]*]|"[^"]*""""), "")
            code.contains('d') || code.contains('y') || (code.contains('m') && !code.contains('0'))
        }.map { it.getAttribute("numFmtId").toInt() }.toSet()
        val cellXfs = root.children("cellXfs").firstOrNull() ?: return emptySet()
        val xfs = cellXfs.children("xf")
        return xfs.indices.filter { i ->
            val id = xfs[i].getAttribute("numFmtId").toIntOrNull() ?: 0
            id in BUILTIN_DATE_FORMATS || id in customDate
        }.toSet()
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
        return n - 1
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>, dateStyles: Set<Int>): SheetData {
        val root = doc(bytes).documentElement
        val out = mutableListOf<List<String>>()
        for (row in root.children("row")) {
            val rowIdx = (row.getAttribute("r").toIntOrNull() ?: (out.size + 1)) - 1
            while (out.size < rowIdx) out.add(emptyList())
            val cells = mutableListOf<String>()
            for (c in row.children("c")) {
                val ref = c.getAttribute("r")
                val col = if (ref.isNotEmpty()) colIndex(ref) else cells.size
                while (cells.size < col) cells.add("")
                val t = c.getAttribute("t")
                val v = c.children("v").firstOrNull()?.textContent
                val style = c.getAttribute("s").toIntOrNull()
                val value = when (t) {
                    "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> c.children("t").joinToString("") { it.textContent }
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    "str", "e" -> v.orEmpty()
                    else -> {
                        val d = v?.toDoubleOrNull()
                        when {
                            d == null -> v.orEmpty()
                            style != null && style in dateStyles && d in 1.0..2958465.0 -> DateParsing.fromExcelSerial(d).toString()
                            else -> plainNumber(d)
                        }
                    }
                }
                cells.add(value)
            }
            out.add(cells)
        }
        return SheetData(out)
    }
}

internal fun plainNumber(d: Double): String =
    if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else java.math.BigDecimal(d.toString()).toPlainString()
