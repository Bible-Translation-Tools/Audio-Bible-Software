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

/**
 * Characterizes
 * [org.bibletranslationtools.otter.common.persistence.database.daos.SubtreeHasResourceDao].
 * Both inserts return the affected-row count from `execute()`, NOT a generated id.
 */
abstract class SubtreeHasResourceDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `single insert returns 1 and is readable back`() {
        val chain = insertProjectChain()
        val count = db.subtreeHasResourceDao.insert(chain.project.id, chain.metadata.id)
        assertEquals(1, count)
        assertEquals(listOf(chain.metadata.id), db.subtreeHasResourceDao.fetchDublinCoreIdsByCollectionId(chain.project.id))
    }

    @Test
    fun `sequence insert returns the number of pairs inserted`() {
        val chain = insertProjectChain()
        val meta2 = insertMetadata(chain.language.id, identifier = "tn", version = "1")

        val count = db.subtreeHasResourceDao.insert(
            sequenceOf(
                chain.project.id to chain.metadata.id,
                chain.project.id to meta2.id,
            )
        )
        assertEquals(2, count)
        assertEquals(
            setOf(chain.metadata.id, meta2.id),
            db.subtreeHasResourceDao.fetchDublinCoreIdsByCollectionId(chain.project.id).toSet(),
        )
    }

    @Test
    fun `fetchDublinCoreIdsByCollectionId returns empty for an unknown collection`() {
        assertEquals(emptyList(), db.subtreeHasResourceDao.fetchDublinCoreIdsByCollectionId(9999))
    }
}

class JooqSubtreeHasResourceDaoCharacterizationTest : SubtreeHasResourceDaoCharacterization() {
    override val backend = JooqBackend
}
