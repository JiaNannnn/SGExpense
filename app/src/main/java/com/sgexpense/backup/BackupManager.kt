package com.sgexpense.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import com.sgexpense.data.db.AppDatabase
import com.sgexpense.data.model.Category
import com.sgexpense.data.model.MerchantRule
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.model.TxType
import com.sgexpense.util.Money
import com.sgexpense.util.toLocalDateTime
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter

/**
 * JSON backup/restore and CSV/XLSX export. Files are written through the system file picker,
 * so Google Drive can be chosen as the destination directly.
 */
class BackupManager(private val context: Context, private val db: AppDatabase) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /** A file (e.g. on Google Drive) the weekly job overwrites. */
    var autoBackupUri: Uri?
        get() = prefs.getString("auto_uri", null)?.let(Uri::parse)
        set(v) { prefs.edit().putString("auto_uri", v?.toString()).apply() }

    var lastBackupAt: Long
        get() = prefs.getLong("last_backup", 0)
        set(v) { prefs.edit().putLong("last_backup", v).apply() }

    var lastBackupError: String?
        get() = prefs.getString("last_error", null)
        set(v) { prefs.edit().putString("last_error", v).apply() }

    fun setAutoBackupTarget(uri: Uri?) {
        autoBackupUri?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        autoBackupUri = uri
    }

    suspend fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "SgExpense")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("categories", JSONArray().apply {
            db.categories().getAll().forEach { c ->
                put(JSONObject().put("id", c.id).put("name", c.name).put("emoji", c.emoji).put("color", c.color)
                    .put("isIncome", c.isIncome).put("sortOrder", c.sortOrder))
            }
        })
        root.put("rules", JSONArray().apply {
            db.rules().getAll().forEach { r ->
                put(JSONObject().put("pattern", r.pattern).put("categoryId", r.categoryId).put("learned", r.learned))
            }
        })
        root.put("transactions", JSONArray().apply {
            db.transactions().getAll().forEach { t ->
                put(JSONObject().put("id", t.id).put("amountCents", t.amountCents).put("currency", t.currency)
                    .put("type", t.type.name).put("merchant", t.merchant).put("categoryId", t.categoryId ?: JSONObject.NULL)
                    .put("paymentMethod", t.paymentMethod.name).put("account", t.account ?: JSONObject.NULL)
                    .put("occurredAt", t.occurredAt).put("note", t.note).put("cashbackCents", t.cashbackCents)
                    .put("source", t.source.name).put("sourceApp", t.sourceApp ?: JSONObject.NULL)
                    .put("rawText", t.rawText ?: JSONObject.NULL).put("createdAt", t.createdAt))
            }
        })
        return root.toString()
    }

    suspend fun writeBackup(uri: Uri) {
        val json = exportJson()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } ?: error("Cannot write to file")
        lastBackupAt = System.currentTimeMillis()
        lastBackupError = null
    }

    /** Replaces all data with the backup's contents. Returns the number of transactions restored. */
    suspend fun restore(uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Cannot open file")
        val root = JSONObject(text)
        require(root.optString("app") == "SgExpense") { "Not an SG Expense backup file" }
        val cats = root.getJSONArray("categories")
        val rules = root.optJSONArray("rules") ?: JSONArray()
        val txs = root.getJSONArray("transactions")
        db.withTransaction {
            db.transactions().deleteAll()
            db.rules().deleteAll()
            db.categories().deleteAll()
            db.categories().insertAll((0 until cats.length()).map { i ->
                val o = cats.getJSONObject(i)
                Category(o.getLong("id"), o.getString("name"), o.getString("emoji"), o.getLong("color"), o.optBoolean("isIncome"), o.optInt("sortOrder"))
            })
            db.rules().insertAll((0 until rules.length()).map { i ->
                val o = rules.getJSONObject(i)
                MerchantRule(pattern = o.getString("pattern"), categoryId = o.getLong("categoryId"), learned = o.optBoolean("learned"))
            })
            db.transactions().insertAll((0 until txs.length()).map { i ->
                val o = txs.getJSONObject(i)
                Transaction(
                    id = o.getLong("id"),
                    amountCents = o.getLong("amountCents"),
                    currency = o.optString("currency", "SGD"),
                    type = runCatching { TxType.valueOf(o.getString("type")) }.getOrDefault(TxType.EXPENSE),
                    merchant = o.optString("merchant"),
                    categoryId = if (o.isNull("categoryId")) null else o.getLong("categoryId"),
                    paymentMethod = runCatching { PaymentMethod.valueOf(o.getString("paymentMethod")) }.getOrDefault(PaymentMethod.OTHER),
                    account = o.optStringOrNull("account"),
                    occurredAt = o.getLong("occurredAt"),
                    note = o.optString("note"),
                    cashbackCents = o.optLong("cashbackCents"),
                    source = runCatching { Source.valueOf(o.getString("source")) }.getOrDefault(Source.MANUAL),
                    sourceApp = o.optStringOrNull("sourceApp"),
                    rawText = o.optStringOrNull("rawText"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            })
        }
        return txs.length()
    }

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k)

    /** Export rows for CSV/XLSX. */
    private suspend fun exportRows(from: Long, to: Long): List<List<Any>> {
        val cats = db.categories().getAll().associateBy { it.id }
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        val header = listOf<Any>("Date", "Time", "Type", "Amount (SGD)", "Merchant", "Category", "Payment method", "Account", "Cashback", "Note", "Source")
        return listOf(header) + db.transactions().getRange(from, to).map { t ->
            val dt = t.occurredAt.toLocalDateTime()
            listOf<Any>(
                dt.format(dateFmt), dt.format(timeFmt), t.type.label,
                (if (t.type == TxType.EXPENSE) -t.amountCents else t.amountCents) / 100.0,
                t.merchant, t.categoryId?.let { cats[it]?.name }.orEmpty(), t.paymentMethod.label, t.account.orEmpty(),
                t.cashbackCents / 100.0, t.note, t.source.label,
            )
        }
    }

    suspend fun exportCsv(uri: Uri, from: Long, to: Long): Int {
        val rows = exportRows(from, to)
        val csv = buildString {
            append('﻿') // so Excel opens UTF-8 correctly
            rows.forEach { r ->
                append(r.joinToString(",") { v ->
                    val s = if (v is Double) Money.plain(Math.round(v * 100)) else v.toString()
                    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
                })
                append("\r\n")
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray()) } ?: error("Cannot write to file")
        return rows.size - 1
    }

    suspend fun exportXlsx(uri: Uri, from: Long, to: Long): Int {
        val rows = exportRows(from, to)
        context.contentResolver.openOutputStream(uri, "wt")?.use { XlsxWriter.write(it, "Transactions", rows) } ?: error("Cannot write to file")
        return rows.size - 1
    }
}
