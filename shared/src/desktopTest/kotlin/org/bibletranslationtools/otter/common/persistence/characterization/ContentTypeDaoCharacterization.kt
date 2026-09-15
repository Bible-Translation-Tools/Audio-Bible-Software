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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.ContentTypeDao].
 *
 * This DAO is an in-memory enum cache over the `content_type` table. On a fresh v14 database the
 * table starts EMPTY and is lazily seeded on the first `fetchId`/`fetchForId` access, which inserts
 * the missing [ContentType] rows via a true RETURNING clause. Backend-agnostic; a concrete subclass
 * supplies the backend.
 */
abstract class ContentTypeDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `fetchId returns a stable positive id`() {
        val dao = db.contentTypeDao
        val id = dao.fetchId(ContentType.TEXT)
        assertTrue(id > 0)
        // Stable across repeated calls.
        assertEquals(id, dao.fetchId(ContentType.TEXT))
    }

    @Test
    fun `fetchForId round-trips a seeded id back to its enum value`() {
        val dao = db.contentTypeDao
        val id = dao.fetchId(ContentType.META)
        assertEquals(ContentType.META, dao.fetchForId(id))
    }

    @Test
    fun `fetchForId returns null for an unknown id`() {
        val dao = db.contentTypeDao
        // Force lazy seeding first so the cache is populated.
        dao.fetchId(ContentType.TEXT)
        assertNull(dao.fetchForId(9999))
    }

    @Test
    fun `lazy seeding assigns distinct contiguous ids to every enum value`() {
        val dao = db.contentTypeDao
        // First access seeds all values into the empty table in declaration order.
        val ids = ContentType.values().map { dao.fetchId(it) }

        // Distinct ids, one per value.
        assertEquals(ContentType.values().size, ids.toSet().size)
        // Fresh empty AUTOINCREMENT table -> ids are the contiguous range 1..N.
        assertEquals((1..ContentType.values().size).toSet(), ids.toSet())
        // Every id round-trips back to its own enum value.
        ContentType.values().forEach { type ->
            assertEquals(type, dao.fetchForId(dao.fetchId(type)))
        }
    }
}

/** jOOQ backend binding for [ContentTypeDaoCharacterization]. */
class JooqContentTypeDaoCharacterizationTest : ContentTypeDaoCharacterization() {
    override val backend = JooqBackend
}
