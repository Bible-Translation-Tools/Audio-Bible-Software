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
import kotlin.test.assertTrue

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.TakeDao].
 */
abstract class TakeDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `insert returns the generated id and round-trips`() {
        val chain = insertProjectChain()
        val content = insertContent(chain.project.id)
        val dao = db.takeDao

        val id = dao.insert(take(content.id, db.checkingStatusDao.fetchId(org.bibletranslationtools.otter.common.data.primitives.CheckingStatus.UNCHECKED), number = 2))
        assertEquals(1, id)

        val fetched = dao.fetchById(id)
        assertEquals(content.id, fetched.contentFk)
        assertEquals(2, fetched.number)
    }

    @Test
    fun `insert returns max id across successive inserts`() {
        val chain = insertProjectChain()
        val content = insertContent(chain.project.id)
        assertEquals(1, insertTake(content.id, number = 1).id)
        assertEquals(2, insertTake(content.id, number = 2).id)
    }

    @Test
    fun `fetchByContentId excludes soft-deleted unless requested`() {
        val chain = insertProjectChain()
        val content = insertContent(chain.project.id)
        insertTake(content.id, number = 1)
        insertTake(content.id, number = 2, deletedTs = "2024-02-02T00:00:00")
        val dao = db.takeDao

        assertEquals(listOf(1), dao.fetchByContentId(content.id).map { it.number })
        assertEquals(setOf(1, 2), dao.fetchByContentId(content.id, includeDeleted = true).map { it.number }.toSet())
    }

    @Test
    fun `update mutates columns`() {
        val chain = insertProjectChain()
        val content = insertContent(chain.project.id)
        val take = insertTake(content.id, number = 1)
        val dao = db.takeDao

        dao.update(take.apply { played = 1; filename = "renamed.wav"; checksum = "abc" })
        val reloaded = dao.fetchById(take.id)
        assertEquals(1, reloaded.played)
        assertEquals("renamed.wav", reloaded.filename)
        assertEquals("abc", reloaded.checksum)
    }

    @Test
    fun `delete removes the row`() {
        val chain = insertProjectChain()
        val content = insertContent(chain.project.id)
        val take = insertTake(content.id, number = 1)
        db.takeDao.delete(take)
        assertTrue(db.takeDao.fetchAll().isEmpty())
    }

    @Test
    fun `fetchSoftDeletedTakes returns all soft-deleted rows`() {
        val chain = insertProjectChain()
        val content = insertContent(chain.project.id)
        insertTake(content.id, number = 1)
        insertTake(content.id, number = 2, deletedTs = "2024-02-02T00:00:00")

        val softDeleted = db.takeDao.fetchSoftDeletedTakes()
        assertEquals(listOf(2), softDeleted.map { it.number })
    }

    @Test
    fun `fetchSoftDeletedTakes(project) returns soft-deleted takes under the project's chapters`() {
        val chain = insertProjectChain()
        // chapter collection is a child of the project; its content holds the takes.
        val chapter = insertCollection(chain.metadata.id, slug = "gen_1", label = "chapter", parentFk = chain.project.id)
        val content = insertContent(chapter.id)
        insertTake(content.id, number = 1) // not deleted
        insertTake(content.id, number = 2, deletedTs = "2024-02-02T00:00:00") // deleted

        val result = db.takeDao.fetchSoftDeletedTakes(chain.project)
        assertEquals(listOf(2), result.map { it.number })
    }

    @Test
    fun `fetchByCollectionId returns takes whose content is in the collection`() {
        val chain = insertProjectChain()
        val content = insertContent(chain.project.id)
        insertTake(content.id, number = 1)
        insertTake(content.id, number = 2, deletedTs = "2024-02-02T00:00:00")
        val dao = db.takeDao

        assertEquals(listOf(1), dao.fetchByCollectionId(chain.project.id).map { it.number })
        assertEquals(setOf(1, 2), dao.fetchByCollectionId(chain.project.id, includeDeleted = true).map { it.number }.toSet())
    }
}

class JooqTakeDaoCharacterizationTest : TakeDaoCharacterization() {
    override val backend = JooqBackend
}
