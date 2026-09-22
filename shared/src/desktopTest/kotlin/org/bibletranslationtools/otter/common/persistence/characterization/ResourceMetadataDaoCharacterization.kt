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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.ResourceMetadataDao].
 * Backend-agnostic; a concrete subclass supplies the backend.
 */
abstract class ResourceMetadataDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `insert returns the generated id and round-trips all 16 columns`() {
        val lang = insertLanguage("en")
        val id = db.resourceMetadataDao.insert(metadata(lang.id))
        assertEquals(1, id)

        val fetched = db.resourceMetadataDao.fetchById(id)!!
        assertEquals("rc0.2", fetched.conformsTo)
        assertEquals("creator", fetched.creator)
        assertEquals("desc", fetched.description)
        assertEquals("text/usfm", fetched.format)
        assertEquals("ulb", fetched.identifier)
        assertEquals("2024-01-01", fetched.issued)
        assertEquals(lang.id, fetched.languageFk)
        assertEquals("2024-01-01", fetched.modified)
        assertEquals("pub", fetched.publisher)
        assertEquals("Bible", fetched.subject)
        assertEquals("book", fetched.type)
        assertEquals("Title", fetched.title)
        assertEquals("1", fetched.version)
        assertEquals("", fetched.license)
        assertEquals("/path/ulb-1", fetched.path)
        assertNull(fetched.derivedFromFk)
    }

    @Test
    fun `insert returns max id across successive inserts`() {
        val lang = insertLanguage("en")
        assertEquals(1, db.resourceMetadataDao.insert(metadata(lang.id, identifier = "ulb")))
        assertEquals(2, db.resourceMetadataDao.insert(metadata(lang.id, identifier = "udb")))
        assertEquals(3, db.resourceMetadataDao.insert(metadata(lang.id, identifier = "reg")))
    }

    @Test
    fun `fetchById returns the row or null`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        assertEquals(meta.id, db.resourceMetadataDao.fetchById(meta.id)!!.id)
        assertNull(db.resourceMetadataDao.fetchById(9999))
    }

    @Test
    fun `fetchByIds returns matching rows and empty for empty input`() {
        val lang = insertLanguage("en")
        val a = insertMetadata(lang.id, identifier = "ulb")
        val b = insertMetadata(lang.id, identifier = "udb")
        insertMetadata(lang.id, identifier = "reg")

        assertEquals(
            setOf(a.id, b.id),
            db.resourceMetadataDao.fetchByIds(listOf(a.id, b.id)).map { it.id }.toSet()
        )
        assertTrue(db.resourceMetadataDao.fetchByIds(emptyList()).isEmpty())
    }

    @Test
    fun `fetchAll returns every row`() {
        val lang = insertLanguage("en")
        insertMetadata(lang.id, identifier = "ulb")
        insertMetadata(lang.id, identifier = "udb")
        assertEquals(2, db.resourceMetadataDao.fetchAll().size)
    }

    @Test
    fun `exists reflects presence of a matching row`() {
        val lang = insertLanguage("en")
        insertMetadata(lang.id, identifier = "ulb", version = "1", creator = "creator")

        assertTrue(db.resourceMetadataDao.exists(lang.id, "ulb", "1", "creator"))
        assertFalse(db.resourceMetadataDao.exists(lang.id, "ulb", "1", "someone-else"))
        assertFalse(db.resourceMetadataDao.exists(lang.id, "missing", "1", "creator"))
    }

    @Test
    fun `fetch returns the matching row or null`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id, identifier = "ulb", version = "1", creator = "creator")

        assertEquals(meta.id, db.resourceMetadataDao.fetch(lang.id, "ulb", "1", "creator")!!.id)
        assertNull(db.resourceMetadataDao.fetch(lang.id, "ulb", "1", "nobody"))
    }

    @Test
    fun `update mutates the columns`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val entity = db.resourceMetadataDao.fetchById(meta.id)!!.apply {
            title = "Changed"
            version = "9"
            creator = "new-creator"
            license = "CC-BY"
        }
        db.resourceMetadataDao.update(entity)

        val reloaded = db.resourceMetadataDao.fetchById(meta.id)!!
        assertEquals("Changed", reloaded.title)
        assertEquals("9", reloaded.version)
        assertEquals("new-creator", reloaded.creator)
        assertEquals("CC-BY", reloaded.license)
    }

    @Test
    fun `delete removes the row`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        db.resourceMetadataDao.delete(db.resourceMetadataDao.fetchById(meta.id)!!)
        assertNull(db.resourceMetadataDao.fetchById(meta.id))
    }

    @Test
    fun `addLink and fetchLinks return the other side and are idempotent`() {
        val lang = insertLanguage("en")
        val a = insertMetadata(lang.id, identifier = "ulb")
        val b = insertMetadata(lang.id, identifier = "udb")

        db.resourceMetadataDao.addLink(a.id, b.id)

        // fetchLinks(a) yields the OTHER side (b), and vice versa.
        assertEquals(listOf(b.id), db.resourceMetadataDao.fetchLinks(a.id).map { it.id })
        assertEquals(listOf(a.id), db.resourceMetadataDao.fetchLinks(b.id).map { it.id })

        // Re-adding the same link (either order) is idempotent — the DAO swallows the exception.
        db.resourceMetadataDao.addLink(a.id, b.id)
        db.resourceMetadataDao.addLink(b.id, a.id)
        assertEquals(1, db.resourceMetadataDao.fetchLinks(a.id).size)
    }

    @Test
    fun `removeLink deletes the link`() {
        val lang = insertLanguage("en")
        val a = insertMetadata(lang.id, identifier = "ulb")
        val b = insertMetadata(lang.id, identifier = "udb")

        db.resourceMetadataDao.addLink(a.id, b.id)
        db.resourceMetadataDao.removeLink(a.id, b.id)
        assertTrue(db.resourceMetadataDao.fetchLinks(a.id).isEmpty())
    }

    @Test
    fun `fetchLatestVersion returns the highest version`() {
        val lang = insertLanguage("en")
        insertMetadata(lang.id, identifier = "ulb", version = "1")
        insertMetadata(lang.id, identifier = "ulb", version = "2")

        val latest = db.resourceMetadataDao.fetchLatestVersion(
            languageSlug = "en",
            identifier = "ulb",
            creator = "creator",
            derivedFromFk = null,
        )
        assertEquals("2", latest!!.version)
    }

    @Test
    fun `fetchLatestVersion relaxes the creator filter when there is no match`() {
        val lang = insertLanguage("en")
        val row = insertMetadata(lang.id, identifier = "ulb", version = "1", creator = "A")

        // No row for creator "B"; relax=true retries with creator=null and finds the "A" row.
        assertEquals(
            row.id,
            db.resourceMetadataDao.fetchLatestVersion(
                languageSlug = "en",
                identifier = "ulb",
                creator = "B",
                derivedFromFk = null,
                relaxCreatorIfNoMatch = true,
            )!!.id
        )

        // relax=false does not retry, so no match -> null.
        assertNull(
            db.resourceMetadataDao.fetchLatestVersion(
                languageSlug = "en",
                identifier = "ulb",
                creator = "B",
                derivedFromFk = null,
                relaxCreatorIfNoMatch = false,
            )
        )
    }

    @Test
    fun `fetchLatestVersion two-arg returns the highest version`() {
        val lang = insertLanguage("en")
        insertMetadata(lang.id, identifier = "ulb", version = "1")
        insertMetadata(lang.id, identifier = "ulb", version = "2")

        assertEquals("2", db.resourceMetadataDao.fetchLatestVersion("en", "ulb")!!.version)
    }
}

/** jOOQ backend binding for [ResourceMetadataDaoCharacterization]. */
class JooqResourceMetadataDaoCharacterizationTest : ResourceMetadataDaoCharacterization() {
    override val backend = JooqBackend
}
