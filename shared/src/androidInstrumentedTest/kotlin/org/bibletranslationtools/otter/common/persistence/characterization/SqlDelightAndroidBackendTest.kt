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

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.common.persistence.entities.TakeEntity
import org.bibletranslationtools.otter.db.OtterDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 5b's on-device gate (docs/phase5b-handoff.md): proves the production SQLDelight backend
 * works on the FRAMEWORK sqlite engine on a real Android 7 device (API 24 / SQLite 3.9.2 — the
 * pre-upsert engine), not just on desktop JVM sqlite-jdbc. A representative slice of the desktop
 * characterization suite (not the full 104-test suite — source-set sharing isn't worth it here):
 * the hand-rolled upsert (`insertAll` dedupe), lazy enum seeding, a full project-chain insert, and
 * a foreign-key cascade (proving FKs are actually ON, not just requested).
 */
@RunWith(AndroidJUnit4::class)
class SqlDelightAndroidBackendTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: SqlDelightAppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Dumb callback: onCreate/onUpgrade are no-ops so SqlDelightAppDatabase.createFresh (which
        // runs OtterDatabase.Schema.create itself) owns schema creation, exactly like the
        // production DI wiring in SharedModules.android.kt. onConfigure enables foreign keys.
        val callback = object : AndroidSqliteDriver.Callback(OtterDatabase.Schema) {
            override fun onCreate(db: SupportSQLiteDatabase) { /* no-op */ }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { /* no-op */ }
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        }
        // In-memory: fine per the handoff spec, and keeps every test isolated + fast.
        driver = AndroidSqliteDriver(OtterDatabase.Schema, context, name = null, callback = callback)
        db = SqlDelightAppDatabase.createFresh(driver)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    // Test method names are plain identifiers (no spaces/backticks): D8 dexing for minSdk 24
    // rejects space characters in method SimpleNames ("prior to DEX version 040"), even though
    // the .class file itself compiles fine with a backtick name.
    @Test
    fun deviceReportsAPreUpsertFrameworkSqlite() {
        val version = sqliteVersion(driver)
        assertTrue("expected an sqlite 3.x version string, got '$version'", version.startsWith("3."))
        android.util.Log.i("SqlDelightAndroidBackendTest", "sqlite_version() = $version")
    }

    @Test
    fun languageDaoInsertRoundTripsById() {
        val id = db.languageDao.insert(language("en", name = "English", gateway = 1, region = "NA"))
        assertEquals(1, id)

        val fetched = db.languageDao.fetchById(id)!!
        assertEquals("en", fetched.slug)
        assertEquals("English", fetched.name)
        assertEquals(1, fetched.gateway)
        assertEquals("NA", fetched.region)
    }

    @Test
    fun languageDaoInsertAllHandRolledUpsertSkipsDuplicateSlug() {
        val existing = db.languageDao.insert(language("en")) // id 1

        // 'en' duplicates the existing slug; the hand-rolled "INSERT...WHERE NOT EXISTS" upsert
        // (no ON CONFLICT DO UPDATE -- unsupported pre-3.24) must skip it without erroring on 3.9.2.
        val ids = db.languageDao.insertAll(listOf(language("es"), language("fr"), language("en")))

        assertEquals(1, existing)
        assertEquals(listOf(2, 3), ids)
        assertEquals(setOf("en", "es", "fr"), db.languageDao.fetchAll().map { it.slug }.toSet())
    }

    @Test
    fun contentTypeDaoFetchIdLazilySeedsTheEnumRowOnFirstAccess() {
        val id = db.contentTypeDao.fetchId(ContentType.TEXT)
        assertEquals(ContentType.TEXT, db.contentTypeDao.fetchForId(id))
        // Second call must return the SAME id, not seed a duplicate row.
        assertEquals(id, db.contentTypeDao.fetchId(ContentType.TEXT))
    }

    @Test
    fun projectChainInsertLanguageToDublinCoreToCollectionToContentToTake() {
        val lang = language("en", gateway = 1).let { it.copy(id = db.languageDao.insert(it)) }
        val meta = metadata(lang.id).let { it.copy(id = db.resourceMetadataDao.insert(it)) }
        val collection = collection(meta.id, "gen").let { it.copy(id = db.collectionDao.insert(it)) }
        val typeId = db.contentTypeDao.fetchId(ContentType.TEXT)
        val content = content(collection.id, typeId).let { it.copy(id = db.contentDao.insert(it)) }
        val checkingId = db.checkingStatusDao.fetchId(CheckingStatus.UNCHECKED)
        val take = take(content.id, checkingId).let { it.copy(id = db.takeDao.insert(it)) }

        assertTrue(lang.id > 0)
        assertTrue(meta.id > 0)
        assertTrue(collection.id > 0)
        assertTrue(content.id > 0)
        assertTrue(take.id > 0)
        assertEquals(content.id, db.takeDao.fetchById(take.id).contentFk)
        assertEquals(collection.id, db.contentDao.fetchById(content.id).collectionFk)
    }

    @Test
    fun deletingACollectionCascadesToItsContentRowsProvesFKsAreOn() {
        val lang = language("en", gateway = 1).let { it.copy(id = db.languageDao.insert(it)) }
        val meta = metadata(lang.id).let { it.copy(id = db.resourceMetadataDao.insert(it)) }
        val collection = collection(meta.id, "gen").let { it.copy(id = db.collectionDao.insert(it)) }
        val typeId = db.contentTypeDao.fetchId(ContentType.TEXT)
        db.contentDao.insert(content(collection.id, typeId, sort = 1, start = 1))
        db.contentDao.insert(content(collection.id, typeId, sort = 2, start = 2))

        assertEquals(2, db.contentDao.fetchByCollectionId(collection.id).size)

        db.collectionDao.delete(collection)

        // content_entity.collection_fk is declared ON DELETE CASCADE; if foreign_keys were OFF
        // (as they default to in SQLite) these rows would survive as orphans.
        assertTrue(db.contentDao.fetchByCollectionId(collection.id).isEmpty())
    }

    // ── fixture builders (mirrors AbstractDatabaseCharacterizationTest in desktopTest) ──────────

    private fun language(
        slug: String,
        name: String = slug.uppercase(),
        anglicized: String = name,
        direction: String = "ltr",
        gateway: Int = 0,
        region: String = "region",
    ) = LanguageEntity(0, slug, name, anglicized, direction, gateway, region)

    private fun metadata(
        languageFk: Int,
        identifier: String = "ulb",
        version: String = "1",
    ) = ResourceMetadataEntity(
        id = 0,
        conformsTo = "rc0.2",
        creator = "creator",
        description = "desc",
        format = "text/usfm",
        identifier = identifier,
        issued = "2024-01-01",
        languageFk = languageFk,
        modified = "2024-01-01",
        publisher = "pub",
        subject = "Bible",
        type = "book",
        title = "Title",
        version = version,
        license = "",
        path = "/path/$identifier-$version",
        derivedFromFk = null,
    )

    private fun collection(dublinCoreFk: Int?, slug: String, label: String = "project", sort: Int = 1) =
        CollectionEntity(0, null, null, label, slug, slug, sort, dublinCoreFk, null)

    private fun content(collectionFk: Int, typeFk: Int, sort: Int = 1, start: Int = 1) = ContentEntity(
        id = 0,
        sort = sort,
        labelKey = "verse",
        start = start,
        end = start,
        collectionFk = collectionFk,
        selectedTakeFk = null,
        text = "text",
        format = "text/usfm",
        type_fk = typeFk,
        draftNumber = 1,
        bridged = false,
    )

    private fun take(contentFk: Int, checkingFk: Int, number: Int = 1) = TakeEntity(
        id = 0,
        contentFk = contentFk,
        filename = "take$number.wav",
        filepath = "/takes/take$number.wav",
        number = number,
        createdTs = "2024-01-01T00:00:00",
        deletedTs = null,
        played = 0,
        checkingFk = checkingFk,
        checksum = null,
    )

    private fun sqliteVersion(driver: SqlDriver): String =
        driver.executeQuery(
            null,
            "SELECT sqlite_version()",
            { cursor ->
                val value = if (cursor.next().value) cursor.getString(0) else null
                QueryResult.Value(value ?: "")
            },
            0
        ).value
}
