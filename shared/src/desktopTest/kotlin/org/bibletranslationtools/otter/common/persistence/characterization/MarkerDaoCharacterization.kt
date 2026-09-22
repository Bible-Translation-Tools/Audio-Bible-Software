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
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.MarkerDao].
 */
abstract class MarkerDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    /** project → content → take, returning the take id (markers hang off a take). */
    private fun takeId(): Int {
        val chain = insertProjectChain()
        val content = insertContent(chain.project.id)
        return insertTake(content.id, number = 1).id
    }

    @Test
    fun `insert returns the generated id and round-trips`() {
        val take = takeId()
        val id = db.markerDao.insert(marker(take, number = 3, position = 42, label = "3"))
        assertEquals(1, id)

        val fetched = db.markerDao.fetchById(id)
        assertEquals(take, fetched.takeFk)
        assertEquals(3, fetched.number)
        assertEquals(42, fetched.position)
        assertEquals("3", fetched.label)
    }

    @Test
    fun `insert returns max id across successive inserts`() {
        val take = takeId()
        assertEquals(1, db.markerDao.insert(marker(take, number = 1)))
        assertEquals(2, db.markerDao.insert(marker(take, number = 2)))
    }

    @Test
    fun `fetchByTakeId returns only that take's markers`() {
        val take = takeId()
        db.markerDao.insert(marker(take, number = 1, position = 10))
        db.markerDao.insert(marker(take, number = 2, position = 20))

        val markers = db.markerDao.fetchByTakeId(take)
        assertEquals(setOf(1, 2), markers.map { it.number }.toSet())
        assertTrue(db.markerDao.fetchByTakeId(9999).isEmpty())
    }

    @Test
    fun `update mutates columns and delete removes the row`() {
        val take = takeId()
        val id = db.markerDao.insert(marker(take, number = 1, position = 10, label = "1"))

        db.markerDao.update(db.markerDao.fetchById(id).apply { position = 99; label = "x" })
        val reloaded = db.markerDao.fetchById(id)
        assertEquals(99, reloaded.position)
        assertEquals("x", reloaded.label)

        db.markerDao.delete(reloaded)
        assertTrue(db.markerDao.fetchAll().isEmpty())
    }
}

class JooqMarkerDaoCharacterizationTest : MarkerDaoCharacterization() {
    override val backend = JooqBackend
}
