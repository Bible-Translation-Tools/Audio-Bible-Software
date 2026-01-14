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

import org.bibletranslationtools.otter.common.persistence.database.AppDatabase
import org.bibletranslationtools.otter.common.persistence.database.Language_entity
import org.bibletranslationtools.otter.common.persistence.database.InsertionException
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.entities.LanguageEntity

class SqlDelightLanguageDao(
    private val db: AppDatabase
) {
    private val queries = db.appDatabaseQueries

    fun fetchGateway(): List<LanguageEntity> {
        return queries.fetchGateway().executeAsList().map { it.toLanguageEntity() }
    }

    fun fetchTargets(): List<LanguageEntity> {
        return queries.fetchTargets().executeAsList().map { it.toLanguageEntity() }
    }

    fun fetchBySlug(slug: String): LanguageEntity? {
        return queries.fetchBySlug(slug).executeAsOneOrNull()?.toLanguageEntity()
    }

    fun insert(entity: LanguageEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")

        queries.insertLanguage(
            slug = entity.slug,
            name = entity.name,
            anglicized = entity.anglicizedName,
            direction = entity.direction,
            gateway = entity.gateway.toLong(),
            region = entity.region
        )

        return queries.lastInsertId().executeAsOne().toInt()
    }

    fun insertAll(entities: List<LanguageEntity>): List<Int> {
        return db.transactionWithResult {
            entities.map { entity ->
                queries.insertLanguage(
                    slug = entity.slug,
                    name = entity.name,
                    anglicized = entity.anglicizedName,
                    direction = entity.direction,
                    gateway = entity.gateway.toLong(),
                    region = entity.region
                )
                queries.lastInsertId().executeAsOne().toInt()
            }
        }
    }

    fun updateRegions(entities: List<LanguageEntity>) {
        db.transaction {
            entities.forEach { entity ->
                queries.updateRegion(region = entity.region, slug = entity.slug)
            }
        }
    }

    fun fetchById(id: Int): LanguageEntity? {
        return queries.fetchLanguageById(id.toLong()).executeAsOneOrNull()?.toLanguageEntity()
    }

    fun fetchAll(): List<LanguageEntity> {
        return queries.fetchAllLanguages().executeAsList().map { it.toLanguageEntity() }
    }

    fun update(entity: LanguageEntity) {
        queries.updateLanguage(
            slug = entity.slug,
            name = entity.name,
            anglicized = entity.anglicizedName,
            direction = entity.direction,
            gateway = entity.gateway.toLong(),
            region = entity.region,
            id = entity.id.toLong()
        )
    }

    fun updateAll(entities: List<LanguageEntity>) {
        db.transaction {
            entities.forEach { entity ->
                queries.updateLanguage(
                    slug = entity.slug,
                    name = entity.name,
                    anglicized = entity.anglicizedName,
                    direction = entity.direction,
                    gateway = entity.gateway.toLong(),
                    region = entity.region,
                    id = entity.id.toLong()
                )
            }
        }
    }

    fun delete(entity: LanguageEntity) {
        queries.deleteLanguageById(entity.id.toLong())
    }
}

fun Language_entity.toLanguageEntity(): LanguageEntity {
    return LanguageEntity(
        id = id.toInt(),
        slug = slug,
        name = name,
        anglicizedName = anglicized,
        direction = direction,
        gateway = gateway.toInt(),
        region = region ?: ""
    )
}
