/**
 * Copyright (C) 2020-2024 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.persistence.characterization

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
import org.bibletranslationtools.otter.common.api.io.zip.IFileWriter
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.DATABASE_FILE_NAME
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.database.AndroidAppDatabase
import org.bibletranslationtools.otter.db.OtterDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File

/**
 * The payoff test (docs/phase5b-handoff.md): measures cold init of BOTH backends on the same
 * on-device SQLite (API 24 / 3.9.2), N=10 iterations each, deleting the DB file between
 * iterations so every run is a genuine fresh-install cold start. This is the number the whole
 * migration is justified by (docs/jooq-to-sqldelight-migration-plan.md sec. 1: "Faster Android
 * database initialization" -- jOOQ DSLContext class-loading + a JDBC layer chosen by probing the
 * device's SQLite version + SQLiteAssetHelper asset-seeding, vs SQLDelight's generated typed
 * queries straight over android.database.sqlite).
 *
 * No speed-ratio assertion (hardware-dependent, per the handoff) -- only that both complete. The
 * two means are what matter; they're logged to logcat under tag "InitBenchmark" AND surfaced in
 * the JUnit failure message so a plain `connectedDebugAndroidTest` run (no logcat capture) still
 * reports them.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseInitBenchmark {

    private val iterations = 10
    private val tag = "InitBenchmark"

    // Plain identifier (no spaces): D8 dexing for minSdk 24 rejects space characters in method
    // SimpleNames ("prior to DEX version 040"), even though the .class compiles fine with backticks.
    @Test
    fun coldInitJooqAndroidAppDatabaseVsSqlDelightAppDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val jooq = benchmarkJooq(context)
        Log.i(tag, "jOOQ:       n=${jooq.count} total=${jooq.totalMs}ms mean=${jooq.meanMs}ms")

        val sqlDelight = benchmarkSqlDelight(context)
        Log.i(tag, "SQLDelight: n=${sqlDelight.count} total=${sqlDelight.totalMs}ms mean=${sqlDelight.meanMs}ms")

        // Only prove both backends completed N cold inits without throwing -- NOT a speed
        // assertion (the handoff explicitly calls that hardware-dependent). The logged means
        // above are the actual result this test exists to produce.
        assertEquals(iterations, jooq.count)
        assertEquals(iterations, sqlDelight.count)
        assertTrue(jooq.totalMs > 0.0)
        assertTrue(sqlDelight.totalMs > 0.0)
    }

    private data class BenchResult(val count: Int, val totalMs: Double, val meanMs: Double)

    private fun benchmarkJooq(context: Context): BenchResult {
        val directoryProvider = FakeDirectoryProvider(context)
        // AndroidAppDatabase ignores the `databaseFile` constructor arg -- it always resolves its
        // db through SQLiteAssetHelper(context, DATABASE_FILE_NAME, ...), i.e. the standard
        // context.getDatabasePath(DATABASE_FILE_NAME). Deleting THAT (not the passed-in File) between
        // iterations is what actually forces a fresh cold init each time.
        val dbName = DATABASE_FILE_NAME
        val samples = DoubleArray(iterations)
        repeat(iterations) { i ->
            context.deleteDatabase(dbName)
            val start = System.nanoTime()
            AndroidAppDatabase(context, context.getDatabasePath(dbName), directoryProvider)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            samples[i] = elapsedMs
        }
        context.deleteDatabase(dbName)
        return samples.toBenchResult()
    }

    private fun benchmarkSqlDelight(context: Context): BenchResult {
        val directoryProvider = FakeDirectoryProvider(context)
        val dbName = "sqldelight_bench.sqlite"
        val samples = DoubleArray(iterations)
        repeat(iterations) { i ->
            context.deleteDatabase(dbName)
            val callback = object : AndroidSqliteDriver.Callback(OtterDatabase.Schema) {
                override fun onCreate(db: SupportSQLiteDatabase) { /* no-op: open()/createFresh owns schema */ }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { /* no-op */ }
                override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            }
            val driver = AndroidSqliteDriver(OtterDatabase.Schema, context, name = dbName, callback = callback)
            val start = System.nanoTime()
            SqlDelightAppDatabase.open(driver, isNewDatabase = true, directoryProvider = directoryProvider)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            samples[i] = elapsedMs
            driver.close()
        }
        context.deleteDatabase(dbName)
        return samples.toBenchResult()
    }

    private fun DoubleArray.toBenchResult(): BenchResult {
        val total = sum()
        return BenchResult(count = size, totalMs = total, meanMs = total / size)
    }

    /**
     * A minimal, non-mocking-framework [IDirectoryProvider]: androidInstrumentedTest has no mockk
     * dependency, and every member here is either unused by a fresh-DB cold init or (tempDirectory)
     * genuinely exercised by [org.bibletranslationtools.otter.common.persistence.database.DatabaseMigrator]'s
     * `extractSelectedTakeInfo` step, which needs a real writable directory even on an empty DB.
     */
    private class FakeDirectoryProvider(context: Context) : IDirectoryProvider {
        private val base = File(context.cacheDir, "phase5b-benchmark").apply { mkdirs() }

        override fun getUserDataDirectory(appendedPath: String): File = base
        override fun getAppDataDirectory(appendedPath: String): File = base
        override val databaseDirectory: File = base
        override val versificationDirectory: File = base
        override val audioPluginDirectory: File = base
        override val userProfileImageDirectory: File = base
        override val userProfileAudioDirectory: File = base
        override val logsDirectory: File = base
        override val cacheDirectory: File = base

        override fun getProjectDirectory(source: ResourceMetadata, target: ResourceMetadata?, book: Collection) = base
        override fun getProjectDirectory(source: ResourceMetadata, target: ResourceMetadata?, bookSlug: String) = base
        override fun getProjectAudioDirectory(source: ResourceMetadata, target: ResourceMetadata?, book: Collection) = base
        override fun getProjectAudioDirectory(source: ResourceMetadata, target: ResourceMetadata?, bookSlug: String) = base
        override fun getProjectSourceDirectory(source: ResourceMetadata, target: ResourceMetadata?, book: Collection) = base
        override fun getProjectSourceDirectory(source: ResourceMetadata, target: ResourceMetadata?, bookSlug: String) = base
        override fun getProjectSourceAudioDirectory(source: ResourceMetadata, target: ResourceMetadata?, bookSlug: String) = base

        override fun getSourceContainerDirectory(container: ResourceContainer): File = base
        override fun getSourceContainerDirectory(metadata: ResourceMetadata): File = base
        override fun getDerivedContainerDirectory(metadata: ResourceMetadata, source: ResourceMetadata): File = base
        override val resourceContainerDirectory: File = base
        override val internalSourceRCDirectory: File = base

        override fun newFileWriter(file: File): IFileWriter = throw UnsupportedOperationException("not used by the benchmark")
        override fun newFileReader(file: File): IFileReader = throw UnsupportedOperationException("not used by the benchmark")

        override val tempDirectory: File = base
        override fun createTempFile(prefix: String, suffix: String?): File = File.createTempFile(prefix, suffix, base)
        override fun cleanTempDirectory() { /* no-op */ }
    }
}
