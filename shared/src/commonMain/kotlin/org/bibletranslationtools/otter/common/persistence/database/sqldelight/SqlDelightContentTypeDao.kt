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

import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.persistence.database.dao.ContentTypeDao
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [ContentTypeDao]. Mirrors the jOOQ ContentTypeDao's stateful lazy seeding: the
 * enum→id map is built once from the existing rows (matched case-insensitively by name), and any
 * enum value not yet present is inserted and added to the map.
 */
internal class SqlDelightContentTypeDao(private val db: OtterDatabase) : ContentTypeDao {
    private val queries = db.contentTypeQueries

    private val mapToId: Map<ContentType, Int> by lazy { loadToDatabase() }
    private val mapToEnum: Map<Int, ContentType> by lazy { mapToId.entries.associate { (k, v) -> v to k } }

    override fun fetchId(contentType: ContentType): Int {
        return mapToId[contentType]
            ?: throw IllegalStateException("$contentType is missing from ContentType table.")
    }

    override fun fetchForId(databaseId: Int): ContentType? = mapToEnum[databaseId]

    private fun loadToDatabase(): Map<ContentType, Int> {
        val enumMap = LinkedHashMap<ContentType, Int>()
        enumMap.putAll(getAll())

        ContentType.values()
            .filterNot { it in enumMap } // exclude existing items
            .forEach { enumMap[it] = insert(it) } // insert new items to db

        return enumMap
    }

    private fun insert(contentType: ContentType): Int {
        return db.transactionWithResult {
            queries.insert(contentType.name)
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    private fun getAll(): Map<ContentType, Int> {
        val nameLookup = ContentType.values().associate { it.name.lowercase() to it }
        return queries.selectAll().executeAsList()
            .mapNotNull { row ->
                nameLookup[row.name.lowercase()]?.let { Pair(it, row.id) }
            }
            .associate { it }
    }
}
