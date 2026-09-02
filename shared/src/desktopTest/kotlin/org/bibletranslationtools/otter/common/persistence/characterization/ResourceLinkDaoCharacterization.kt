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

import org.bibletranslationtools.otter.common.persistence.entities.resourceLinkEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Characterizes the non-jOOQ-Select surface of
 * [org.bibletranslationtools.otter.common.persistence.database.daos.ResourceLinkDao].
 *
 * NOT covered here (they take a raw jOOQ `Select<Record3<Int,Int,Int>>` and can only be exercised
 * through the resource-container import path): `insertContentResourceNoReturn`,
 * `insertCollectionResourceNoReturn`. Those are pinned by the integration tests in Phase 4.
 *
 * resource_link has a CHECK constraint: exactly one of content_fk / collection_fk is non-null.
 */
abstract class ResourceLinkDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `insert a content-link returns the id and round-trips`() {
        val chain = insertProjectChain()
        val resource = insertContent(chain.project.id, start = 1)
        val target = insertContent(chain.project.id, start = 2)
        val dao = db.resourceLinkDao

        val id = dao.insert(resourceLinkEntity(resource, target, chain.metadata))
        assertEquals(1, id)

        val fetched = dao.fetchById(id)
        assertEquals(resource.id, fetched.resourceContentFk)
        assertEquals(target.id, fetched.contentFk)
        assertEquals(null, fetched.collectionFk)
        assertEquals(chain.metadata.id, fetched.dublinCoreFk)
    }

    @Test
    fun `fetchByContentId returns links targeting that content`() {
        val chain = insertProjectChain()
        val resource = insertContent(chain.project.id, start = 1)
        val target = insertContent(chain.project.id, start = 2)
        db.resourceLinkDao.insert(resourceLinkEntity(resource, target, chain.metadata))

        assertEquals(1, db.resourceLinkDao.fetchByContentId(target.id).size)
        assertEquals(0, db.resourceLinkDao.fetchByContentId(resource.id).size)
    }

    @Test
    fun `fetchByCollectionId returns collection-links`() {
        val chain = insertProjectChain()
        val resource = insertContent(chain.project.id, start = 1)
        db.resourceLinkDao.insert(resourceLinkEntity(resource, chain.project, chain.metadata))

        assertEquals(1, db.resourceLinkDao.fetchByCollectionId(chain.project.id).size)
    }

    @Test
    fun `insertNoReturn inserts every row in the batch`() {
        val chain = insertProjectChain()
        val resource = insertContent(chain.project.id, start = 1)
        val t1 = insertContent(chain.project.id, start = 2)
        val t2 = insertContent(chain.project.id, start = 3)

        db.resourceLinkDao.insertNoReturn(
            resourceLinkEntity(resource, t1, chain.metadata),
            resourceLinkEntity(resource, t2, chain.metadata),
        )
        assertEquals(2, db.resourceLinkDao.fetchAll().size)
    }

    @Test
    fun `update mutates and delete removes`() {
        val chain = insertProjectChain()
        val resource = insertContent(chain.project.id, start = 1)
        val target = insertContent(chain.project.id, start = 2)
        val other = insertContent(chain.project.id, start = 3)
        val dao = db.resourceLinkDao
        val id = dao.insert(resourceLinkEntity(resource, target, chain.metadata))

        dao.update(dao.fetchById(id).apply { contentFk = other.id })
        assertEquals(other.id, dao.fetchById(id).contentFk)

        dao.delete(dao.fetchById(id))
        assertEquals(0, dao.fetchAll().size)
    }
}

class JooqResourceLinkDaoCharacterizationTest : ResourceLinkDaoCharacterization() {
    override val backend = JooqBackend
}
