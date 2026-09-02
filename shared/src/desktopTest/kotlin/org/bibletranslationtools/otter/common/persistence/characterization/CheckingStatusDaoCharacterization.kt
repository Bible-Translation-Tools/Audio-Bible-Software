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

import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.CheckingStatusDao].
 *
 * Same enum-cache pattern as `ContentTypeDao`, over the `checking_status` table: empty on a fresh
 * v14 database and lazily seeded on first access. Backend-agnostic; a concrete subclass supplies the
 * backend.
 */
abstract class CheckingStatusDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `fetchId lazily seeds a fresh database and returns a stable positive id`() {
        val dao = db.checkingStatusDao
        // First-ever access on an empty table must succeed via lazy seeding.
        val id = dao.fetchId(CheckingStatus.UNCHECKED)
        assertTrue(id > 0)
        assertEquals(id, dao.fetchId(CheckingStatus.UNCHECKED))
    }

    @Test
    fun `fetchById round-trips a seeded id back to its enum value`() {
        val dao = db.checkingStatusDao
        val id = dao.fetchId(CheckingStatus.VERSE)
        assertEquals(CheckingStatus.VERSE, dao.fetchById(id))
    }

    @Test
    fun `fetchById returns null for an unknown id`() {
        val dao = db.checkingStatusDao
        dao.fetchId(CheckingStatus.UNCHECKED) // populate the cache
        assertNull(dao.fetchById(9999))
    }

    @Test
    fun `every enum value maps to a distinct contiguous id`() {
        val dao = db.checkingStatusDao
        val ids = CheckingStatus.values().map { dao.fetchId(it) }

        assertEquals(CheckingStatus.values().size, ids.toSet().size)
        assertEquals((1..CheckingStatus.values().size).toSet(), ids.toSet())
        CheckingStatus.values().forEach { status ->
            assertEquals(status, dao.fetchById(dao.fetchId(status)))
        }
    }
}

/** jOOQ backend binding for [CheckingStatusDaoCharacterization]. */
class JooqCheckingStatusDaoCharacterizationTest : CheckingStatusDaoCharacterization() {
    override val backend = JooqBackend
}
