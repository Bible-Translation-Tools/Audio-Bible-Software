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

import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.WorkbookTypeDao].
 *
 * Same enum-cache pattern as the other enum DAOs, over the `workbook_type` table keyed by
 * [ProjectMode]: empty on a fresh v14 database and lazily seeded on first access. Backend-agnostic;
 * a concrete subclass supplies the backend.
 */
abstract class WorkbookTypeDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `fetchId lazily seeds a fresh database and returns a stable positive id`() {
        val dao = db.workbookTypeDao
        val id = dao.fetchId(ProjectMode.TRANSLATION)
        assertTrue(id > 0)
        assertEquals(id, dao.fetchId(ProjectMode.TRANSLATION))
    }

    @Test
    fun `fetchById round-trips a seeded id back to its enum value`() {
        val dao = db.workbookTypeDao
        val id = dao.fetchId(ProjectMode.DIALECT)
        assertEquals(ProjectMode.DIALECT, dao.fetchById(id))
    }

    @Test
    fun `fetchById returns null for an unknown id`() {
        val dao = db.workbookTypeDao
        dao.fetchId(ProjectMode.TRANSLATION) // populate the cache
        assertNull(dao.fetchById(9999))
    }

    @Test
    fun `every enum value maps to a distinct contiguous id`() {
        val dao = db.workbookTypeDao
        val ids = ProjectMode.values().map { dao.fetchId(it) }

        assertEquals(ProjectMode.values().size, ids.toSet().size)
        assertEquals((1..ProjectMode.values().size).toSet(), ids.toSet())
        ProjectMode.values().forEach { mode ->
            assertEquals(mode, dao.fetchById(dao.fetchId(mode)))
        }
    }
}

/** jOOQ backend binding for [WorkbookTypeDaoCharacterization]. */
class JooqWorkbookTypeDaoCharacterizationTest : WorkbookTypeDaoCharacterization() {
    override val backend = JooqBackend
}
