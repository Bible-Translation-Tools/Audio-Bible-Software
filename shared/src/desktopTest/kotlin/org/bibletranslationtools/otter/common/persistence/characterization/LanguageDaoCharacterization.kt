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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.LanguageDao].
 * Backend-agnostic; a concrete subclass supplies the backend.
 */
abstract class LanguageDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `insert returns the generated id and round-trips the row`() {
        val dao = db.languageDao
        val id = dao.insert(language("en", name = "English", gateway = 1, region = "NA"))
        assertEquals(1, id)

        val fetched = dao.fetchById(id)!!
        assertEquals("en", fetched.slug)
        assertEquals("English", fetched.name)
        assertEquals(1, fetched.gateway)
        assertEquals("NA", fetched.region)
    }

    @Test
    fun `insert returns max id across successive inserts`() {
        val dao = db.languageDao
        assertEquals(1, dao.insert(language("en")))
        assertEquals(2, dao.insert(language("es")))
        assertEquals(3, dao.insert(language("fr")))
    }

    @Test
    fun `fetchGateway and fetchTargets partition on the gateway flag`() {
        val dao = db.languageDao
        dao.insert(language("en", gateway = 1))
        dao.insert(language("es", gateway = 1))
        dao.insert(language("xyz", gateway = 0))

        assertEquals(setOf("en", "es"), dao.fetchGateway().map { it.slug }.toSet())
        assertEquals(listOf("xyz"), dao.fetchTargets().map { it.slug })
    }

    @Test
    fun `fetchBySlug returns the row or null`() {
        val dao = db.languageDao
        dao.insert(language("en"))
        assertEquals("en", dao.fetchBySlug("en")?.slug)
        assertNull(dao.fetchBySlug("missing"))
    }

    @Test
    fun `fetchByIds returns matching rows and empty for empty input`() {
        val dao = db.languageDao
        val a = dao.insert(language("en"))
        val b = dao.insert(language("es"))
        dao.insert(language("fr"))

        assertEquals(setOf(a, b), dao.fetchByIds(listOf(a, b)).map { it.id }.toSet())
        assertTrue(dao.fetchByIds(emptyList()).isEmpty())
    }

    @Test
    fun `update mutates all columns`() {
        val dao = db.languageDao
        val id = dao.insert(language("en", name = "English"))
        val entity = dao.fetchById(id)!!.apply {
            name = "Changed"
            anglicizedName = "Changed"
            direction = "rtl"
            gateway = 1
            region = "EU"
        }
        dao.update(entity)

        val reloaded = dao.fetchById(id)!!
        assertEquals("Changed", reloaded.name)
        assertEquals("rtl", reloaded.direction)
        assertEquals(1, reloaded.gateway)
        assertEquals("EU", reloaded.region)
    }

    @Test
    fun `delete removes the row`() {
        val dao = db.languageDao
        val id = dao.insert(language("en"))
        dao.delete(dao.fetchById(id)!!)
        assertNull(dao.fetchById(id))
    }

    @Test
    fun `insertAll returns the contiguous id range and skips duplicate slugs`() {
        val dao = db.languageDao
        // Seed one existing row so the returned range starts above it.
        val existing = dao.insert(language("en")) // id 1

        // 'en' duplicates the existing slug -> onConflictDoNothing skips it, but the returned
        // range is still derived from max(id) before/after, i.e. (initialMax+1 .. finalMax).
        val ids = dao.insertAll(listOf(language("es"), language("fr"), language("en")))

        assertEquals(1, existing)
        assertEquals(listOf(2, 3), ids)
        assertEquals(setOf("en", "es", "fr"), dao.fetchAll().map { it.slug }.toSet())
    }

    @Test
    fun `updateAll updates existing rows by slug and inserts the missing ones`() {
        val dao = db.languageDao
        dao.insert(language("en", name = "English"))

        dao.updateAll(
            listOf(
                language("en", name = "English-Updated"),
                language("es", name = "Spanish"),
            )
        )

        assertEquals("English-Updated", dao.fetchBySlug("en")!!.name)
        assertEquals("Spanish", dao.fetchBySlug("es")!!.name)
        assertEquals(2, dao.fetchAll().size)
    }
}

/** jOOQ backend binding for [LanguageDaoCharacterization]. */
class JooqLanguageDaoCharacterizationTest : LanguageDaoCharacterization() {
    override val backend = JooqBackend
}
