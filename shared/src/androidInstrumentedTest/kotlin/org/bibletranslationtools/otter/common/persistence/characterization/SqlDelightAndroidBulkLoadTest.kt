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
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.db.OtterDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device coverage for [SqlDelightAppDatabase.withBulkLoad] (the first-install seed path). A
 * FILE-backed DB is required — WAL does not apply to an in-memory database. This is the test that
 * catches platform-specific driver behavior the desktop JdbcSqliteDriver tolerates but Android's
 * framework driver does not (e.g. a row-returning PRAGMA must use query/rawQuery, not execute()).
 * Method names are plain identifiers: D8 rejects spaces in a SimpleName for minSdk 24.
 */
@RunWith(AndroidJUnit4::class)
class SqlDelightAndroidBulkLoadTest {

    private lateinit var context: Context
    private lateinit var driver: SqlDriver
    private lateinit var db: SqlDelightAppDatabase
    private val dbName = "bulkload-test.sqlite"

    @After
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        listOf("", "-wal", "-shm").forEach { context.getDatabasePath("$dbName$it").delete() }
    }

    @Test
    fun withBulkLoadSeedsOnDeviceKeepsIndexAndEnablesWal() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("", "-wal", "-shm").forEach { context.getDatabasePath("$dbName$it").delete() }

        val callback = object : AndroidSqliteDriver.Callback(OtterDatabase.Schema) {
            override fun onCreate(db: SupportSQLiteDatabase) { /* no-op: createFresh owns schema */ }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { /* no-op */ }
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        }
        driver = AndroidSqliteDriver(OtterDatabase.Schema, context, name = dbName, callback = callback)
        db = SqlDelightAppDatabase.createFresh(driver)

        // This is where the pre-fix crash happened (PRAGMA journal_mode=WAL via execute()).
        db.withBulkLoad { seedOneVerse() }

        assertEquals("seeded content should be present", 1, db.contentDao.fetchAll().size)
        assertTrue("content index must remain present", indexExists("idx_content_entity_collection_start"))
        assertEquals("bulk load should have switched the db to WAL", "wal", journalMode())
    }

    private fun seedOneVerse() {
        val langId = db.languageDao.insert(LanguageEntity(0, "en", "English", "English", "ltr", 1, "US"))
        val metaId = db.resourceMetadataDao.insert(
            ResourceMetadataEntity(
                0, "rc0.2", "creator", "desc", "text/usfm", "ulb", "2024-01-01", langId, "2024-01-01",
                "pub", "Bible", "book", "Title", "1", "", "/data/ulb", null
            )
        )
        val collId = db.collectionDao.insert(
            CollectionEntity(0, null, null, "project", "Genesis", "gen", 1, metaId, null)
        )
        db.contentDao.insertNoReturn(
            ContentEntity(
                id = 0, sort = 1, labelKey = "verse", start = 1, end = 1, collectionFk = collId,
                selectedTakeFk = null, text = "text", format = "text/usfm",
                type_fk = db.contentTypeDao.fetchId(ContentType.TEXT), draftNumber = 1, bridged = false
            )
        )
    }

    private fun journalMode(): String =
        driver.executeQuery(null, "PRAGMA journal_mode;", { c -> c.next(); QueryResult.Value(c.getString(0)!!) }, 0)
            .value.lowercase()

    private fun indexExists(name: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='$name';",
            { c -> c.next(); QueryResult.Value((c.getLong(0) ?: 0L) > 0L) },
            0
        ).value
}
