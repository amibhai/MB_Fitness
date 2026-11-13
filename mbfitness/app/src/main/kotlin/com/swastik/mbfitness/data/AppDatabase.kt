package com.swastik.mbfitness.data

import android.content.Context
import android.database.SQLException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.swastik.mbfitness.domain.PackLanguage
import com.swastik.mbfitness.domain.TemplatePacks

@Database(
    version = AppDatabase.VERSION,
    exportSchema = true,
    entities = [
        PlanEntity::class,
        MemberEntity::class,
        PaymentEntity::class,
        FreezeEntity::class,
        MemberEventEntity::class,
        MessageEntity::class,
        SendAttemptEntity::class,
        TemplateEntity::class,
        BroadcastEntity::class,
        QuietDateEntity::class,
        ErrorLogEntity::class,
        CounterEntity::class,
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun plans(): PlanDao
    abstract fun members(): MemberDao
    abstract fun payments(): PaymentDao
    abstract fun freezes(): FreezeDao
    abstract fun events(): MemberEventDao
    abstract fun messages(): MessageDao
    abstract fun sendAttempts(): SendAttemptDao
    abstract fun templates(): TemplateDao
    abstract fun broadcasts(): BroadcastDao
    abstract fun quietDates(): QuietDateDao
    abstract fun errorLog(): ErrorLogDao
    abstract fun counters(): CounterDao
    abstract fun backup(): BackupDao

    /** SPEC 12: "ok" from SQLite's own check. Anything else, including an error, means damaged. */
    fun passesQuickCheck(): Boolean = try {
        openHelper.readableDatabase.query("PRAGMA quick_check").use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == "ok"
        }
    } catch (_: SQLException) {
        false
    }

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "mbfitness.db"
        const val RECEIPT_COUNTER = "next_receipt_no"

        /** When 1.0.2 first opened, in millis. Kept with the data, so it travels in backups. */
        const val JOINING_FIX_CUTOFF = "joining_fix_cutoff"

        /** Every schema change adds one here, with a test (hard rule 7). None yet. */
        val ALL_MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context, onDamaged: () -> Unit): AppDatabase =
            configure(Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME))
                .openHelperFactory(KeepDamagedDatabaseFactory(onDamaged))
                .build()

        fun configure(builder: Builder<AppDatabase>): Builder<AppDatabase> =
            builder.addMigrations(*ALL_MIGRATIONS).addCallback(SeedCallback)
    }

    /** Runs inside the transaction that creates the database, so it can't half-finish. */
    private object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT INTO plans (name, months, price, isActive, sortOrder) VALUES (?, ?, ?, 1, ?)",
                arrayOf<Any>("1 month", 1, 1200, 1),
            )
            db.execSQL(
                "INSERT INTO plans (name, months, price, isActive, sortOrder) VALUES (?, ?, ?, 1, ?)",
                arrayOf<Any>("3 months", 3, 3000, 2),
            )
            for (template in TemplatePacks.defaults(PackLanguage.ENGLISH)) {
                db.execSQL(
                    "INSERT INTO templates (type, body, enabled, offsetDays, repeatEveryDays, maxRepeats) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        template.kind.name,
                        template.body,
                        if (template.enabled) 1 else 0,
                        template.offsetDays,
                        template.repeatEveryDays,
                        template.maxRepeats,
                    ),
                )
            }
            db.execSQL(
                "INSERT INTO counters (name, value) VALUES (?, ?)",
                arrayOf<Any>(RECEIPT_COUNTER, 1),
            )
        }
    }
}
