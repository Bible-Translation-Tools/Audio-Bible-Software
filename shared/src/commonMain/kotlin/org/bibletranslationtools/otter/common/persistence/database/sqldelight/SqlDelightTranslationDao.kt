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
package org.bibletranslationtools.otter.common.persistence.database.sqldelight

import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.bibletranslationtools.otter.common.persistence.database.dao.TranslationDao
import org.bibletranslationtools.otter.common.persistence.entities.TranslationEntity
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [TranslationDao]. Behavior mirrors the jOOQ TranslationDao, including the
 * `insert → SELECT max(id)` id retrieval (rates fall back to their schema defaults on insert), the
 * try/catch that turns a failed `fetchById` into null, and deletion by the (source, target) natural
 * key.
 */
internal class SqlDelightTranslationDao(private val db: OtterDatabase) : TranslationDao {
    private val queries = db.translationQueries

    override fun fetch(sourceId: Int, targetId: Int): TranslationEntity? =
        queries.fetch(sourceId, targetId).executeAsOneOrNull()?.toEntity()

    override fun fetchById(id: Int): TranslationEntity? =
        try {
            queries.fetchById(id).executeAsOneOrNull()?.toEntity()
        } catch (e: Exception) {
            null
        }

    override fun fetchAll(): List<TranslationEntity> =
        queries.fetchAll().executeAsList().map { it.toEntity() }

    override fun insert(entity: TranslationEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")
        return db.transactionWithResult {
            queries.insert(
                sourceFk = entity.sourceFk,
                targetFk = entity.targetFk,
                modifiedTs = entity.modifiedTs,
            )
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    override fun update(entity: TranslationEntity) {
        queries.update(
            sourceFk = entity.sourceFk,
            targetFk = entity.targetFk,
            modifiedTs = entity.modifiedTs,
            sourceRate = entity.sourceRate,
            targetRate = entity.targetRate,
            id = entity.id,
        )
    }

    override fun delete(entity: TranslationEntity) {
        queries.deleteByNaturalKey(entity.sourceFk, entity.targetFk)
    }
}
