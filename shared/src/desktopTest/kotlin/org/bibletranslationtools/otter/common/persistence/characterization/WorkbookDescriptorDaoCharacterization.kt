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

/**
 * Characterizes
 * [org.bibletranslationtools.otter.common.persistence.database.daos.WorkbookDescriptorDao].
 * source_fk/target_fk are COLLECTION ids.
 */
abstract class WorkbookDescriptorDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    /** Two sibling collections to act as source and target. */
    private fun sourceTarget(): Pair<Int, Int> {
        val chain = insertProjectChain()
        val target = insertCollection(chain.metadata.id, slug = "gen-target", label = "project", sort = 2)
        return chain.project.id to target.id
    }

    @Test
    fun `insert returns the generated id and round-trips`() {
        val (source, target) = sourceTarget()
        val typeId = db.workbookTypeDao.fetchId(ProjectMode.TRANSLATION)
        val id = db.workbookDescriptorDao.insert(workbookDescriptor(source, target, ProjectMode.TRANSLATION))
        assertEquals(1, id)

        val fetched = db.workbookDescriptorDao.fetchById(id)!!
        assertEquals(source, fetched.sourceFk)
        assertEquals(target, fetched.targetFk)
        assertEquals(typeId, fetched.typeFk)
    }

    @Test
    fun `insert returns max id across successive inserts`() {
        val (source, target) = sourceTarget()
        assertEquals(1, db.workbookDescriptorDao.insert(workbookDescriptor(source, target, ProjectMode.TRANSLATION)))
        assertEquals(2, db.workbookDescriptorDao.insert(workbookDescriptor(source, target, ProjectMode.NARRATION)))
    }

    @Test
    fun `fetch resolves by source, target, and type`() {
        val (source, target) = sourceTarget()
        val typeId = db.workbookTypeDao.fetchId(ProjectMode.TRANSLATION)
        db.workbookDescriptorDao.insert(workbookDescriptor(source, target, ProjectMode.TRANSLATION))

        assertEquals(source, db.workbookDescriptorDao.fetch(source, target, typeId)?.sourceFk)
        assertNull(db.workbookDescriptorDao.fetch(source, target, 9999))
    }

    @Test
    fun `fetchAll returns inserted rows`() {
        val (source, target) = sourceTarget()
        db.workbookDescriptorDao.insert(workbookDescriptor(source, target, ProjectMode.TRANSLATION))
        assertEquals(1, db.workbookDescriptorDao.fetchAll().size)
    }

    @Test
    fun `update mutates the type`() {
        val (source, target) = sourceTarget()
        val id = db.workbookDescriptorDao.insert(workbookDescriptor(source, target, ProjectMode.TRANSLATION))
        val narrationType = db.workbookTypeDao.fetchId(ProjectMode.NARRATION)

        db.workbookDescriptorDao.update(db.workbookDescriptorDao.fetchById(id)!!.apply { typeFk = narrationType })
        assertEquals(narrationType, db.workbookDescriptorDao.fetchById(id)!!.typeFk)
    }

    @Test
    fun `delete removes the row`() {
        val (source, target) = sourceTarget()
        val id = db.workbookDescriptorDao.insert(workbookDescriptor(source, target, ProjectMode.TRANSLATION))
        db.workbookDescriptorDao.delete(db.workbookDescriptorDao.fetchById(id)!!)
        assertNull(db.workbookDescriptorDao.fetchById(id))
    }
}

class JooqWorkbookDescriptorDaoCharacterizationTest : WorkbookDescriptorDaoCharacterization() {
    override val backend = JooqBackend
}
