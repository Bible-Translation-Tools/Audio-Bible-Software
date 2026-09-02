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
import kotlin.test.assertTrue

/**
 * Characterizes [org.bibletranslationtools.otter.common.persistence.database.daos.TranslationDao].
 * source_fk/target_fk are LANGUAGE ids.
 */
abstract class TranslationDaoCharacterization : AbstractDatabaseCharacterizationTest() {

    private fun twoLanguages(): Pair<Int, Int> {
        val source = insertLanguage("en", gateway = 1)
        val target = insertLanguage("es")
        return source.id to target.id
    }

    @Test
    fun `insert returns the generated id and defaults the rates`() {
        val (source, target) = twoLanguages()
        val id = db.translationDao.insert(translation(source, target))
        assertEquals(1, id)

        val fetched = db.translationDao.fetchById(id)!!
        assertEquals(source, fetched.sourceFk)
        assertEquals(target, fetched.targetFk)
        // insert writes only source/target/modified_ts; rates come from the schema defaults.
        assertEquals(1.0, fetched.sourceRate)
        assertEquals(1.0, fetched.targetRate)
    }

    @Test
    fun `insert returns max id across successive inserts`() {
        val (source, target) = twoLanguages()
        val third = insertLanguage("fr").id
        assertEquals(1, db.translationDao.insert(translation(source, target)))
        assertEquals(2, db.translationDao.insert(translation(source, third)))
    }

    @Test
    fun `fetch resolves by source and target`() {
        val (source, target) = twoLanguages()
        db.translationDao.insert(translation(source, target))
        assertEquals(source, db.translationDao.fetch(source, target)?.sourceFk)
        assertNull(db.translationDao.fetch(target, source))
    }

    @Test
    fun `fetchById returns null for a missing id`() {
        assertNull(db.translationDao.fetchById(9999))
    }

    @Test
    fun `update mutates the rates`() {
        val (source, target) = twoLanguages()
        val id = db.translationDao.insert(translation(source, target))

        db.translationDao.update(db.translationDao.fetchById(id)!!.apply { sourceRate = 1.5; targetRate = 0.75 })
        val reloaded = db.translationDao.fetchById(id)!!
        assertEquals(1.5, reloaded.sourceRate)
        assertEquals(0.75, reloaded.targetRate)
    }

    @Test
    fun `delete removes by the source-target natural key`() {
        val (source, target) = twoLanguages()
        db.translationDao.insert(translation(source, target))
        // delete matches on source_fk + target_fk, not id.
        db.translationDao.delete(translation(source, target))
        assertTrue(db.translationDao.fetchAll().isEmpty())
    }
}

class JooqTranslationDaoCharacterizationTest : TranslationDaoCharacterization() {
    override val backend = JooqBackend
}
