package com.swastik.mbfitness.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The scaffold hard rule 7 relies on. When the schema changes: bump AppDatabase.VERSION, add
 * the Migration to ALL_MIGRATIONS, and add a test here that creates the old version, migrates
 * it and checks the data survived.
 *
 * These use the platform driver because Room's default one compares file names in a way that
 * breaks on Windows paths under Robolectric. It doesn't affect the app on a phone.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file: File = context.getDatabasePath("migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = file,
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class,
    )

    @Test
    fun theExportedSchemaMatchesTheCurrentCode() {
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(AppDatabase.VERSION, AppDatabase.ALL_MIGRATIONS.toList()).close()
    }

    @Test
    fun everyVersionUpToTheCurrentOneHasAnExportedSchema() {
        val folder = File("schemas/com.swastik.mbfitness.data.AppDatabase")
        for (version in 1..AppDatabase.VERSION) {
            assertTrue("schema $version is not committed", File(folder, "$version.json").exists())
        }
    }

    /** A database from an old install must open with its data, never be wiped (hard rule 7). */
    @Test
    fun anExistingDatabaseOpensWithItsDataIntact() = runTest {
        helper.createDatabase(1).use { old ->
            old.execSQL("INSERT INTO plans (id, name, months, price, isActive, sortOrder) VALUES (7, 'Old plan', 1, 500, 1, 1)")
        }
        val database = AppDatabase.configure(Room.databaseBuilder(context, AppDatabase::class.java, file.name))
            .setDriver(AndroidSQLiteDriver())
            .build()
        try {
            assertEquals("Old plan", database.plans().get(7)!!.name)
        } finally {
            database.close()
        }
    }
}
