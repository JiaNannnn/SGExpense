package com.sgexpense.importer

import android.content.Context
import android.net.Uri
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.repository.ExpenseRepository
import com.sgexpense.importer.ocr.OcrAnalyzer
import com.sgexpense.importer.ocr.OcrEngine
import com.sgexpense.importer.sheet.ColumnMapping
import com.sgexpense.importer.sheet.SheetData
import com.sgexpense.importer.sheet.SheetReader
import com.sgexpense.importer.sheet.StatementMapper
import com.sgexpense.util.atMillis
import com.sgexpense.util.todaySg

/** A detected row on the review screen. */
data class ReviewItem(
    val key: Int,
    val draft: DraftTransaction,
    val categoryId: Long?,
    val selected: Boolean,
    val duplicate: Boolean,
    val sourceLabel: String,
    val source: Source,
)

sealed interface FileResult {
    data class Drafts(val drafts: List<DraftTransaction>, val mapping: ColumnMapping) : FileResult
    /** Layout not recognised: the user picks the columns. */
    data class NeedsMapping(val sheet: SheetData) : FileResult
}

class ImportService(private val context: Context, private val repo: ExpenseRepository) {
    private val ocr by lazy { OcrEngine(context) }
    private val mappingPrefs = context.getSharedPreferences("import_mapping", Context.MODE_PRIVATE)

    suspend fun analyzeImage(uri: Uri): List<DraftTransaction> = OcrAnalyzer.analyze(ocr.recognizeRows(uri), todaySg())

    fun readSheet(uri: Uri): SheetData {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot open file")
        return SheetReader.read(bytes)
    }

    fun analyzeSheet(sheet: SheetData): FileResult {
        val mapping = StatementMapper.detect(sheet) ?: savedMapping(sheet) ?: return FileResult.NeedsMapping(sheet)
        return FileResult.Drafts(StatementMapper.map(sheet, mapping, todaySg()), mapping)
    }

    fun mapWith(sheet: SheetData, mapping: ColumnMapping): List<DraftTransaction> {
        saveMapping(sheet, mapping)
        return StatementMapper.map(sheet, mapping, todaySg())
    }

    /** Remembers a manual column mapping keyed by the header row text. */
    private fun headerKey(sheet: SheetData, row: Int) = sheet.rows.getOrNull(row)?.joinToString("|")?.lowercase()?.hashCode()?.toString()

    private fun saveMapping(sheet: SheetData, m: ColumnMapping) {
        val key = headerKey(sheet, m.headerRow) ?: return
        mappingPrefs.edit().putString(key, listOf(m.headerRow, m.date, m.description, m.amount, m.withdrawal, m.deposit, if (m.positiveIsExpense) 1 else 0).joinToString(",")).apply()
    }

    private fun savedMapping(sheet: SheetData): ColumnMapping? {
        for (r in 0 until minOf(sheet.rows.size, 40)) {
            val v = mappingPrefs.getString(headerKey(sheet, r) ?: continue, null) ?: continue
            val p = v.split(",").map { it.toInt() }
            if (p[0] != r) continue
            return ColumnMapping(p[0], p[1], p[2], p[3], p[4], p[5], p[6] == 1)
        }
        return null
    }

    suspend fun toReviewItems(drafts: List<DraftTransaction>, label: String, source: Source, startKey: Int): List<ReviewItem> =
        drafts.mapIndexed { i, d ->
            val dup = repo.isLikelyDuplicate(d.amountCents, d.date.atMillis(d.time))
            ReviewItem(
                key = startKey + i,
                draft = d,
                categoryId = repo.categorize(d.merchant, d.type, d.paymentMethod),
                selected = !dup,
                duplicate = dup,
                sourceLabel = label,
                source = source,
            )
        }

    suspend fun commit(items: List<ReviewItem>): Int {
        val txs = items.filter { it.selected }.map {
            Transaction(
                amountCents = it.draft.amountCents,
                type = it.draft.type,
                merchant = it.draft.merchant,
                categoryId = it.categoryId,
                paymentMethod = it.draft.paymentMethod,
                occurredAt = it.draft.date.atMillis(it.draft.time),
                source = it.source,
                rawText = it.draft.raw,
                note = "",
            )
        }
        repo.insertAll(txs)
        return txs.size
    }
}
