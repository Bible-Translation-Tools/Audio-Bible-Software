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
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.CollectionDao].
 * Backend-agnostic; a concrete subclass supplies the backend.
 */
abstract class CollectionDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `insert returns max id across successive inserts`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao

        assertEquals(1, dao.insert(collection(meta.id, "gen")))
        assertEquals(2, dao.insert(collection(meta.id, "exo")))
        assertEquals(3, dao.insert(collection(meta.id, "lev")))
    }

    @Test
    fun `insert round-trips the row`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao
        val id = dao.insert(collection(meta.id, "gen", label = "project", title = "Genesis", sort = 7))

        val fetched = dao.fetchById(id)
        assertEquals("gen", fetched.slug)
        assertEquals("Genesis", fetched.title)
        assertEquals("project", fetched.label)
        assertEquals(7, fetched.sort)
        assertEquals(meta.id, fetched.dublinCoreFk)
        assertNull(fetched.parentFk)
        assertNull(fetched.sourceFk)
    }

    @Test
    fun `fetchAll returns every row`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao
        dao.insert(collection(meta.id, "gen"))
        dao.insert(collection(meta.id, "exo"))

        assertEquals(setOf("gen", "exo"), dao.fetchAll().map { it.slug }.toSet())
    }

    @Test
    fun `fetchByIds returns matching rows and empty for empty input`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao
        val a = dao.insert(collection(meta.id, "gen"))
        val b = dao.insert(collection(meta.id, "exo"))
        dao.insert(collection(meta.id, "lev"))

        assertEquals(setOf(a, b), dao.fetchByIds(listOf(a, b)).map { it.id }.toSet())
        assertTrue(dao.fetchByIds(emptyList()).isEmpty())
    }

    @Test
    fun `fetchChildren returns children by parent ordered by sort`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao
        val parent = insertCollection(meta.id, "gen", label = "project", sort = 1)
        // Insert children out of sort order to prove ordering is by SORT, not id.
        insertCollection(meta.id, "ch3", label = "chapter", sort = 3, parentFk = parent.id)
        insertCollection(meta.id, "ch1", label = "chapter", sort = 1, parentFk = parent.id)
        insertCollection(meta.id, "ch2", label = "chapter", sort = 2, parentFk = parent.id)
        // A collection under a different parent must not appear.
        insertCollection(meta.id, "other", label = "chapter", sort = 1)

        val children = dao.fetchChildren(parent)
        assertEquals(listOf("ch1", "ch2", "ch3"), children.map { it.slug })
    }

    @Test
    fun `fetchSource returns the collection referenced by sourceFk`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao
        val source = insertCollection(meta.id, "gen-src")
        val derived = insertCollection(meta.id, "gen-tgt", sourceFk = source.id)

        val fetched = dao.fetchSource(derived)!!
        assertEquals(source.id, fetched.id)
        assertEquals("gen-src", fetched.slug)
    }

    @Test
    fun `fetchSource returns null when sourceFk is null`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao
        val entity = insertCollection(meta.id, "gen")

        assertNull(dao.fetchSource(entity))
    }

    @Test
    fun `fetch matches on slug, container id, and label`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao
        val project = insertCollection(meta.id, "gen", label = "project")
        // Same slug + container but a different label must not be returned by the default fetch.
        insertCollection(meta.id, "gen", label = "chapter", parentFk = project.id)

        val fetched = dao.fetch("gen", meta.id)!!
        assertEquals(project.id, fetched.id)
        assertEquals("project", fetched.label)

        assertNull(dao.fetch("missing", meta.id))
        assertNull(dao.fetch("gen", meta.id, label = "book"))
    }

    @Test
    fun `fetchByLabel returns all rows with that label`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao
        val project = insertCollection(meta.id, "gen", label = "project")
        insertCollection(meta.id, "ch1", label = "chapter", parentFk = project.id)
        insertCollection(meta.id, "ch2", label = "chapter", parentFk = project.id)

        assertEquals(setOf("ch1", "ch2"), dao.fetchByLabel("chapter").map { it.slug }.toSet())
        assertEquals(listOf("gen"), dao.fetchByLabel("project").map { it.slug })
    }

    @Test
    fun `update mutates all columns`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val other = insertMetadata(lang.id, identifier = "reg")
        val dao = db.collectionDao
        val parent = insertCollection(meta.id, "parent")
        val source = insertCollection(meta.id, "source")
        val id = dao.insert(collection(meta.id, "gen"))

        val entity = dao.fetchById(id).apply {
            parentFk = parent.id
            sourceFk = source.id
            label = "chapter"
            title = "Changed"
            slug = "changed"
            sort = 9
            dublinCoreFk = other.id
            modifiedTs = "2024-06-01"
        }
        dao.update(entity)

        val reloaded = dao.fetchById(id)
        assertEquals(parent.id, reloaded.parentFk)
        assertEquals(source.id, reloaded.sourceFk)
        assertEquals("chapter", reloaded.label)
        assertEquals("Changed", reloaded.title)
        assertEquals("changed", reloaded.slug)
        assertEquals(9, reloaded.sort)
        assertEquals(other.id, reloaded.dublinCoreFk)
        assertEquals("2024-06-01", reloaded.modifiedTs)
    }

    @Test
    fun `delete removes the row`() {
        val lang = insertLanguage("en")
        val meta = insertMetadata(lang.id)
        val dao = db.collectionDao
        val id = dao.insert(collection(meta.id, "gen"))

        dao.delete(dao.fetchById(id))

        assertTrue(dao.fetchByIds(listOf(id)).isEmpty())
    }

    @Test
    fun `collectionsWithoutTakes returns only chapters that have no takes`() {
        val chain = insertProjectChain(langSlug = "en", projectSlug = "gen")
        val meta = chain.metadata
        val project = chain.project

        // Two chapter collections under the project.
        val chapterWithTake = insertCollection(meta.id, "ch1", label = "chapter", sort = 1, parentFk = project.id)
        val chapterWithoutTake = insertCollection(meta.id, "ch2", label = "chapter", sort = 2, parentFk = project.id)

        // TEXT content (type_fk == 1) under each chapter; the query only counts type_fk == 1.
        val contentWithTake = insertContent(chapterWithTake.id)
        insertContent(chapterWithoutTake.id)

        // A single take under only the first chapter's content.
        insertTake(contentWithTake.id)

        val result = db.collectionDao.collectionsWithoutTakes(project)

        assertEquals(listOf(chapterWithoutTake.id), result.map { it.id })
        assertEquals(listOf("ch2"), result.map { it.slug })
    }
}

/** jOOQ backend binding for [CollectionDaoCharacterization]. */
class JooqCollectionDaoCharacterizationTest : CollectionDaoCharacterization() {
    override val backend = JooqBackend
}
