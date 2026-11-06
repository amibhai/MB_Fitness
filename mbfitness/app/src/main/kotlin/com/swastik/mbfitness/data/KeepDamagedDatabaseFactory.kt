package com.swastik.mbfitness.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.io.IOException

/**
 * Android deletes a database it finds corrupt and carries on with an empty one, which would look
 * like a new install with every member gone. This keeps a copy of the damaged file beside it and
 * says so, so the app can offer a restore instead of showing an empty list (SPEC 12).
 */
class KeepDamagedDatabaseFactory(private val onDamaged: () -> Unit) : SupportSQLiteOpenHelper.Factory {
    private val framework = FrameworkSQLiteOpenHelperFactory()

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val inner = configuration.callback
        val guarded = object : SupportSQLiteOpenHelper.Callback(inner.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = inner.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)

            override fun onCorruption(db: SupportSQLiteDatabase) {
                keepCopy(db.path)
                onDamaged()
                inner.onCorruption(db)
            }
        }
        return framework.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(guarded)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build(),
        )
    }

    private fun keepCopy(path: String?) {
        val file = path?.let(::File) ?: return
        try {
            if (file.exists()) file.copyTo(File("$path.damaged"), overwrite = true)
        } catch (_: IOException) {
            // The restore offer matters more than this copy.
        }
    }
}
