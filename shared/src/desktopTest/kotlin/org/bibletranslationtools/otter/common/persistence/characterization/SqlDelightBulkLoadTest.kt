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

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [SqlDelightAppDatabase.withBulkLoad] — the first-install bulk-load path (#2 index drop/recreate,
 * #3 WAL + synchronous=NORMAL + foreign_keys off/on) — which the integration tests bypass (they call the
 * importer directly, never InitializeApp). A file-backed DB is required: WAL does not apply to :memory:.
 */
class SqlDelightBulkLoadTest {

    private val dbFile = File.createTempFile("bulkload-", ".sqlite").apply { delete() }
    private val db = SqlDelightAppDatabase.createFresh(JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}"))

    @AfterTest
    fun cleanup() {
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    @Test
    fun `withBulkLoad seeds a fresh db, keeps the content index, and leaves it WAL with FKs intact`() {
        db.withBulkLoad { seedOneVerse(start = 1) }

        // The block ran and its data survived (the finally's foreign_key_check did not throw).
        assertEquals(1, contentRowCount(), "seeded content should be present")
        // The index is intentionally left in place during the seed (the importer's self-joins need it).
        assertEquals(true, indexExists("idx_content_entity_collection_start"), "content index must remain present")
        // Crash-safe fast journal, applied and persisted.
        assertEquals("wal", journalMode())
    }

    @Test
    fun `withBulkLoad is a plain passthrough once content already exists`() {
        db.withBulkLoad { seedOneVerse(start = 1) }   // fills content, so the gate closes
        db.withBulkLoad { seedAnotherVerse(start = 2) } // should just run the block, no index/PRAGMA churn

        assertEquals(2, contentRowCount(), "the passthrough must still run its block")
    }

    // ── seeding (a minimal language -> dublin_core -> collection -> content chain) ────────────────

    private var collectionId = 0

    private fun seedOneVerse(start: Int) {
        val langId = db.languageDao.insert(
            LanguageEntity(0, "en", "English", "English", "ltr", 1, "US")
        )
        val metaId = db.resourceMetadataDao.insert(
            ResourceMetadataEntity(
                0, "rc0.2", "creator", "desc", "text/usfm", "ulb", "2024-01-01", langId, "2024-01-01",
                "pub", "Bible", "book", "Title", "1", "", "/tmp/ulb", null
            )
        )
        collectionId = db.collectionDao.insert(
            CollectionEntity(0, null, null, "project", "Genesis", "gen", 1, metaId, null)
        )
        seedAnotherVerse(start)
    }

    private fun seedAnotherVerse(start: Int) {
        db.contentDao.insertNoReturn(
            ContentEntity(
                id = 0, sort = start, labelKey = "verse", start = start, end = start,
                collectionFk = collectionId, selectedTakeFk = null, text = "text", format = "text/usfm",
                type_fk = db.contentTypeDao.fetchId(ContentType.TEXT), draftNumber = 1, bridged = false
            )
        )
    }

    // ── introspection over a separate JDBC connection ────────────────────────────────────────────

    private fun <T> read(sql: String, extract: (java.sql.ResultSet) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); extract(rs) } }
        }

    private fun contentRowCount(): Int = read("SELECT count(*) FROM content_entity") { it.getInt(1) }
    private fun journalMode(): String = read("PRAGMA journal_mode") { it.getString(1).lowercase() }
    private fun indexExists(name: String): Boolean =
        read("SELECT count(*) FROM sqlite_master WHERE type='index' AND name='$name'") { it.getInt(1) > 0 }
}
