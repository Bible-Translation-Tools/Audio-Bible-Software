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
package org.bibletranslationtools.otter.common.persistence.database.daos.sqldelight

import org.bibletranslationtools.otter.common.persistence.database.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.database.Translation_entity
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.TranslationEntity

class SqlDelightTranslationDao(
    private val db: SqlDelightAppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun fetch(sourceId: Int, targetId: Int): TranslationEntity? {
        return queries.fetchTranslation(sourceId.toLong(), targetId.toLong()).executeAsOneOrNull()?.toTranslationEntity()
    }

    fun fetchById(id: Int): TranslationEntity? {
        return queries.fetchTranslationById(id.toLong()).executeAsOneOrNull()?.toTranslationEntity()
    }

    fun fetchAll(): List<TranslationEntity> {
        return queries.fetchAllTranslations().executeAsList().map { it.toTranslationEntity() }
    }

    fun insert(entity: TranslationEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")

        queries.insertTranslation(
            source_fk = entity.sourceFk.toLong(),
            target_fk = entity.targetFk.toLong(),
            modified_ts = entity.modifiedTs
        )

        return queries.lastInsertId().executeAsOne().toInt()
    }

    fun update(entity: TranslationEntity) {
        queries.updateTranslation(
            source_fk = entity.sourceFk.toLong(),
            target_fk = entity.targetFk.toLong(),
            modified_ts = entity.modifiedTs,
            source_rate = entity.sourceRate,
            target_rate = entity.targetRate,
            id = entity.id.toLong()
        )
    }

    fun delete(entity: TranslationEntity) {
        queries.deleteTranslation(entity.sourceFk.toLong(), entity.targetFk.toLong())
    }
}

fun Translation_entity.toTranslationEntity(): TranslationEntity {
    return TranslationEntity(
        id = id.toInt(),
        sourceFk = source_fk.toInt(),
        targetFk = target_fk.toInt(),
        modifiedTs = modified_ts,
        sourceRate = source_rate ?: 1.0,
        targetRate = target_rate ?: 1.0
    )
}
