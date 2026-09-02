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
import org.bibletranslationtools.otter.common.persistence.database.dao.LanguageDao
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [LanguageDao]. Behavior mirrors the jOOQ LanguageDao, including the
 * `insert → SELECT max(id)` id retrieval and `insertAll`'s contiguous-id-range return computed from
 * max(id) before/after (with insert-if-absent-by-slug standing in for jOOQ's onConflictDoNothing).
 */
internal class SqlDelightLanguageDao(private val db: OtterDatabase) : LanguageDao {
    private val queries = db.languageQueries

    override fun fetchGateway(): List<LanguageEntity> =
        queries.fetchGateway().executeAsList().map { it.toEntity() }

    override fun fetchTargets(): List<LanguageEntity> =
        queries.fetchTargets().executeAsList().map { it.toEntity() }

    override fun fetchBySlug(slug: String): LanguageEntity? =
        queries.fetchBySlug(slug).executeAsOneOrNull()?.toEntity()

    override fun insert(entity: LanguageEntity): Int {
        if (entity.id != 0) throw InsertionException("Entity ID is not 0")
        return db.transactionWithResult {
            queries.insert(
                slug = entity.slug,
                name = entity.name,
                anglicized = entity.anglicizedName,
                direction = entity.direction,
                gateway = entity.gateway,
                region = entity.region,
            )
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    override fun insertAll(entities: List<LanguageEntity>): List<Int> {
        val initialLargest = queries.selectMaxId().executeAsOne().max ?: 0
        db.transaction {
            entities.forEach { entity ->
                queries.insertIfAbsentBySlug(
                    slug = entity.slug,
                    name = entity.name,
                    anglicized = entity.anglicizedName,
                    direction = entity.direction,
                    gateway = entity.gateway,
                    region = entity.region,
                )
            }
        }
        val finalLargest = queries.selectMaxId().executeAsOne().max!!
        return ((initialLargest + 1)..finalLargest).toList()
    }

    override fun updateRegions(entities: List<LanguageEntity>) {
        db.transaction {
            entities.forEach { queries.updateRegionBySlug(region = it.region, slug = it.slug) }
        }
    }

    override fun fetchById(id: Int): LanguageEntity? =
        queries.fetchById(id).executeAsOneOrNull()?.toEntity()

    override fun fetchByIds(ids: List<Int>): List<LanguageEntity> {
        if (ids.isEmpty()) return emptyList()
        return queries.fetchByIds(ids).executeAsList().map { it.toEntity() }
    }

    override fun fetchAll(): List<LanguageEntity> =
        queries.fetchAll().executeAsList().map { it.toEntity() }

    override fun update(entity: LanguageEntity) {
        queries.update(
            slug = entity.slug,
            name = entity.name,
            anglicized = entity.anglicizedName,
            direction = entity.direction,
            gateway = entity.gateway,
            region = entity.region,
            id = entity.id,
        )
    }

    override fun updateAll(entities: List<LanguageEntity>) {
        val dbSlugs = fetchAll().map { it.slug }.toSet()
        val toInsert = entities.filter { it.slug !in dbSlugs }
        db.transaction {
            entities.forEach {
                queries.updateBySlug(
                    name = it.name,
                    anglicized = it.anglicizedName,
                    direction = it.direction,
                    gateway = it.gateway,
                    region = it.region,
                    slug = it.slug,
                )
            }
        }
        insertAll(toInsert)
    }

    override fun delete(entity: LanguageEntity) {
        queries.delete(entity.id)
    }
}
