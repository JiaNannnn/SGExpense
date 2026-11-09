package com.sgexpense.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.sgexpense.data.model.CaptureLog
import com.sgexpense.data.model.Category
import com.sgexpense.data.model.MerchantRule
import com.sgexpense.data.model.PaymentMethod
import com.sgexpense.data.model.Source
import com.sgexpense.data.model.Transaction
import com.sgexpense.data.model.TxType

class Converters {
    @TypeConverter fun fromMethod(v: PaymentMethod): String = v.name
    @TypeConverter fun toMethod(v: String): PaymentMethod = runCatching { PaymentMethod.valueOf(v) }.getOrDefault(PaymentMethod.OTHER)
    @TypeConverter fun fromType(v: TxType): String = v.name
    @TypeConverter fun toType(v: String): TxType = runCatching { TxType.valueOf(v) }.getOrDefault(TxType.EXPENSE)
    @TypeConverter fun fromSource(v: Source): String = v.name
    @TypeConverter fun toSource(v: String): Source = runCatching { Source.valueOf(v) }.getOrDefault(Source.MANUAL)
}

@Database(
    entities = [Transaction::class, Category::class, MerchantRule::class, CaptureLog::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun categories(): CategoryDao
    abstract fun rules(): MerchantRuleDao
    abstract fun captureLog(): CaptureLogDao

    companion object {
        const val NAME = "sgexpense.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                // TRUNCATE (not WAL) keeps the whole DB in one file, so Android Auto Backup copies a consistent snapshot.
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}
