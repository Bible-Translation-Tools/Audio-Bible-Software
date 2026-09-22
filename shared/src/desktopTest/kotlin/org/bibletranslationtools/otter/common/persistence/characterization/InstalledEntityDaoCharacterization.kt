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

import org.bibletranslationtools.otter.common.persistence.database.DATABASE_INSTALLABLE_NAME
import org.bibletranslationtools.otter.common.persistence.database.SCHEMA_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.InstalledEntityDao].
 *
 * A fresh v14 database already contains one `installed_entity` row, name "DATABASE" version
 * [SCHEMA_VERSION] (14), stamped during bootstrap. `upsert` inserts when the name is absent and
 * updates the version when present; `fetchVersion` returns null for an unknown name and the stored
 * version for a known one. Backend-agnostic; a concrete subclass supplies the backend.
 */
abstract class InstalledEntityDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    @Test
    fun `a fresh database is seeded with the DATABASE entity at the current schema version`() {
        val dao = db.installedEntityDao
        assertEquals(SCHEMA_VERSION, dao.fetchVersion(installable(DATABASE_INSTALLABLE_NAME, 0)))
    }

    @Test
    fun `fetchVersion returns null for an unknown name`() {
        val dao = db.installedEntityDao
        assertNull(dao.fetchVersion(installable("myfeature", 3)))
    }

    @Test
    fun `upsert inserts when the name is absent`() {
        val dao = db.installedEntityDao
        assertNull(dao.fetchVersion(installable("myfeature", 3)))

        dao.upsert(installable("myfeature", 3))

        assertEquals(3, dao.fetchVersion(installable("myfeature", 0)))
    }

    @Test
    fun `upsert updates the version when the name is already present`() {
        val dao = db.installedEntityDao
        dao.upsert(installable("myfeature", 3))
        assertEquals(3, dao.fetchVersion(installable("myfeature", 0)))

        dao.upsert(installable("myfeature", 7))

        assertEquals(7, dao.fetchVersion(installable("myfeature", 0)))
    }
}

/** jOOQ backend binding for [InstalledEntityDaoCharacterization]. */
class JooqInstalledEntityDaoCharacterizationTest : InstalledEntityDaoCharacterization() {
    override val backend = JooqBackend
}
