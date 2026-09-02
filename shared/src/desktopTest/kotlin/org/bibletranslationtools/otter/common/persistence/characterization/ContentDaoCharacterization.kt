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

import org.bibletranslationtools.otter.common.data.primitives.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.ContentDao].
 * Backend-agnostic; a concrete subclass supplies the backend.
 *
 * NOT characterized here (they return raw jOOQ `Select`/`SelectConditionStep` builders that only
 * yield rows once further composed and executed, so they can't be exercised standalone):
 * `selectVerseByCollectionIdAndStart`, `selectLinkableVerses`, `selectLinkableChapters`,
 * `fetchContentByProjectSlug`.
 */
abstract class ContentDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `insert returns the generated id and round-trips the row`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)

        val id = dao.insert(content(project.id, textType, sort = 2, start = 5, end = 7, text = "hello"))
        assertEquals(1, id)

        val fetched = dao.fetchById(id)
        assertEquals(2, fetched.sort)
        assertEquals(5, fetched.start)
        assertEquals(7, fetched.end)
        assertEquals("hello", fetched.text)
        assertEquals(project.id, fetched.collectionFk)
        assertEquals(textType, fetched.type_fk)
    }

    @Test
    fun `insert persists the bridged flag as a round-tripping boolean`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)

        val bridgedId = dao.insert(content(project.id, textType, sort = 1, bridged = true))
        val plainId = dao.insert(content(project.id, textType, sort = 2, bridged = false))

        assertTrue(dao.fetchById(bridgedId).bridged)
        assertFalse(dao.fetchById(plainId).bridged)
    }

    @Test
    fun `insert returns max id across successive inserts`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)

        assertEquals(1, dao.insert(content(project.id, textType, sort = 1)))
        assertEquals(2, dao.insert(content(project.id, textType, sort = 2)))
        assertEquals(3, dao.insert(content(project.id, textType, sort = 3)))
    }

    @Test
    fun `insertNoReturn batch inserts every row`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)

        dao.insertNoReturn(
            content(project.id, textType, sort = 1, start = 1),
            content(project.id, textType, sort = 2, start = 2),
            content(project.id, textType, sort = 3, start = 3),
        )

        assertEquals(3, dao.fetchAll().size)
        assertEquals(setOf(1, 2, 3), dao.fetchAll().map { it.start }.toSet())
    }

    @Test
    fun `fetchAll returns every row`() {
        val dao = db.contentDao
        val project = insertProjectChain().project

        insertContent(project.id, sort = 1)
        insertContent(project.id, sort = 2)

        assertEquals(2, dao.fetchAll().size)
    }

    @Test
    fun `fetchByCollectionId returns rows ordered by sort`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)

        // Insert out of natural order to prove the ORDER BY SORT.
        dao.insert(content(project.id, textType, sort = 3, start = 3))
        dao.insert(content(project.id, textType, sort = 1, start = 1))
        dao.insert(content(project.id, textType, sort = 2, start = 2))

        assertEquals(listOf(1, 2, 3), dao.fetchByCollectionId(project.id).map { it.sort })
    }

    @Test
    fun `fetchByCollectionId filters to the requested collection`() {
        val dao = db.contentDao
        val a = insertProjectChain("en", "gen").project
        val b = insertProjectChain("es", "exo").project

        insertContent(a.id, sort = 1)
        insertContent(b.id, sort = 1)

        assertEquals(a.id, dao.fetchByCollectionId(a.id).single().collectionFk)
    }

    @Test
    fun `fetchByCollectionIdAndType filters by type`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
        val metaType = db.contentTypeDao.fetchId(ContentType.META)

        dao.insert(content(project.id, textType, sort = 1))
        dao.insert(content(project.id, metaType, sort = 2))

        val texts = dao.fetchByCollectionIdAndType(project.id, ContentType.TEXT)
        assertEquals(1, texts.size)
        assertEquals(textType, texts.single().type_fk)
    }

    @Test
    fun `fetchByCollectionIdAndStart filters by start and the given types`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
        val metaType = db.contentTypeDao.fetchId(ContentType.META)

        dao.insert(content(project.id, textType, sort = 1, start = 5))
        dao.insert(content(project.id, metaType, sort = 2, start = 5))
        dao.insert(content(project.id, textType, sort = 3, start = 6))

        val textOnly = dao.fetchByCollectionIdAndStart(project.id, 5, listOf(ContentType.TEXT))
        assertEquals(1, textOnly.size)
        assertEquals(5, textOnly.single().start)
        assertEquals(textType, textOnly.single().type_fk)

        val both = dao.fetchByCollectionIdAndStart(project.id, 5, listOf(ContentType.TEXT, ContentType.META))
        assertEquals(2, both.size)
        assertEquals(setOf(textType, metaType), both.map { it.type_fk }.toSet())
    }

    @Test
    fun `update rewrites all columns including the collection fk`() {
        val dao = db.contentDao
        val a = insertProjectChain("en", "gen").project
        val b = insertProjectChain("es", "exo").project

        val entity = insertContent(a.id, sort = 1, start = 1)
        entity.collectionFk = b.id
        entity.sort = 9
        entity.start = 4
        entity.end = 4
        entity.text = "updated"
        dao.update(entity)

        val reloaded = dao.fetchById(entity.id)
        assertEquals(b.id, reloaded.collectionFk)
        assertEquals(9, reloaded.sort)
        assertEquals(4, reloaded.start)
        assertEquals("updated", reloaded.text)
    }

    @Test
    fun `updateAll rewrites columns but leaves the collection fk untouched`() {
        val dao = db.contentDao
        val a = insertProjectChain("en", "gen").project
        val b = insertProjectChain("es", "exo").project

        val entity = insertContent(a.id, sort = 1)
        entity.collectionFk = b.id // should be ignored by updateAll
        entity.sort = 9
        entity.text = "changed"
        dao.updateAll(listOf(entity))

        val reloaded = dao.fetchById(entity.id)
        assertEquals(a.id, reloaded.collectionFk) // unchanged
        assertEquals(9, reloaded.sort)
        assertEquals("changed", reloaded.text)
    }

    @Test
    fun `delete removes the row`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val entity = insertContent(project.id)

        dao.delete(entity)

        assertTrue(dao.fetchAll().isEmpty())
    }

    @Test
    fun `deleteForCollection defaults to content type id 1 (TEXT)`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
        val metaType = db.contentTypeDao.fetchId(ContentType.META)

        dao.insert(content(project.id, textType, sort = 1))
        dao.insert(content(project.id, metaType, sort = 2))

        dao.deleteForCollection(project) // null -> type id 1 == TEXT

        val remaining = dao.fetchByCollectionId(project.id)
        assertEquals(1, remaining.size)
        assertEquals(metaType, remaining.single().type_fk)
    }

    @Test
    fun `deleteForCollection deletes the explicitly requested type`() {
        val dao = db.contentDao
        val project = insertProjectChain().project
        val textType = db.contentTypeDao.fetchId(ContentType.TEXT)
        val metaType = db.contentTypeDao.fetchId(ContentType.META)

        dao.insert(content(project.id, textType, sort = 1))
        dao.insert(content(project.id, metaType, sort = 2))

        dao.deleteForCollection(project, metaType)

        val remaining = dao.fetchByCollectionId(project.id)
        assertEquals(1, remaining.size)
        assertEquals(textType, remaining.single().type_fk)
    }

    @Test
    fun `updateSources replaces derivative links and fetchSources reads them back`() {
        val dao = db.contentDao
        val project = insertProjectChain().project

        val main = insertContent(project.id, sort = 1)
        val s1 = insertContent(project.id, sort = 2)
        val s2 = insertContent(project.id, sort = 3)

        dao.updateSources(main, listOf(s1, s2))
        assertEquals(setOf(s1.id, s2.id), dao.fetchSources(main).map { it.id }.toSet())

        // Replace: only s2 remains.
        dao.updateSources(main, listOf(s2))
        assertEquals(listOf(s2.id), dao.fetchSources(main).map { it.id })

        // Empty replaces everything.
        dao.updateSources(main, emptyList())
        assertTrue(dao.fetchSources(main).isEmpty())
    }

    @Test
    fun `linkDerivative adds a single source link`() {
        val dao = db.contentDao
        val project = insertProjectChain().project

        val content = insertContent(project.id, sort = 1)
        val source = insertContent(project.id, sort = 2)

        dao.linkDerivative(content.id, source.id)

        assertEquals(listOf(source.id), dao.fetchSources(content).map { it.id })
    }
}

/** jOOQ backend binding for [ContentDaoCharacterization]. */
class JooqContentDaoCharacterizationTest : ContentDaoCharacterization() {
    override val backend = JooqBackend
}
