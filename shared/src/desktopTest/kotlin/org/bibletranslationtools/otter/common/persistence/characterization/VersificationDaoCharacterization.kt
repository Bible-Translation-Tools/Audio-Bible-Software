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

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.VersificationDao].
 *
 * The `versification_entity` table maps a unique `slug` to a file `path`. `upsert` is
 * exception-driven: it tries `insert` and, on the duplicate-slug failure, falls back to `update`
 * (and it ignores its own `dsl` parameter). Backend-agnostic; a concrete subclass supplies the
 * backend.
 */
abstract class VersificationDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `fetchVersificationFile returns null for a missing slug`() {
        val dao = db.versificationDao
        assertNull(dao.fetchVersificationFile("missing"))
    }

    @Test
    fun `insert then fetch round-trips the path`() {
        val dao = db.versificationDao
        dao.insert("ulb", "/versification/ulb.json")
        assertEquals("/versification/ulb.json", dao.fetchVersificationFile("ulb"))
    }

    @Test
    fun `update mutates the path of an existing slug`() {
        val dao = db.versificationDao
        dao.insert("ulb", "/versification/ulb.json")
        dao.update("ulb", "/versification/ulb-v2.json")
        assertEquals("/versification/ulb-v2.json", dao.fetchVersificationFile("ulb"))
    }

    @Test
    fun `upsert inserts when the slug is absent`() {
        val dao = db.versificationDao
        assertNull(dao.fetchVersificationFile("ulb"))

        dao.upsert("ulb", "/versification/ulb.json")

        assertEquals("/versification/ulb.json", dao.fetchVersificationFile("ulb"))
    }

    @Test
    fun `upsert updates the path when the slug is already present`() {
        val dao = db.versificationDao
        dao.insert("ulb", "/versification/ulb.json")

        dao.upsert("ulb", "/versification/ulb-updated.json")

        assertEquals("/versification/ulb-updated.json", dao.fetchVersificationFile("ulb"))
    }
}

/** jOOQ backend binding for [VersificationDaoCharacterization]. */
class JooqVersificationDaoCharacterizationTest : VersificationDaoCharacterization() {
    override val backend = JooqBackend
}
