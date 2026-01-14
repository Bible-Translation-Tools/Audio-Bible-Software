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
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import java.util.EnumMap

class SqlDelightContentTypeDao(
    private val db: AppDatabase
) {
    private val queries = db.appDatabaseQueries
    private val mapToId: Map<ContentType, Int> by lazy { loadDatabaseMap() }
    private val mapToEnum: Map<Int, ContentType> by lazy { mapToId.entries.associate { (k, v) -> v to k } }

    fun fetchId(contentType: ContentType): Int {
        return mapToId[contentType] ?: throw IllegalStateException("$contentType is missing from ContentType table.")
    }

    fun fetchForId(databaseId: Int): ContentType? = mapToEnum[databaseId]

    private fun loadDatabaseMap(): EnumMap<ContentType, Int> {
        val enumMap = EnumMap<ContentType, Int>(ContentType::class.java)
        
        // Populate existing from DB
        val nameLookup = ContentType.entries.associateBy { it.name.lowercase() }
        queries.fetchAllContentType().executeAsList().forEach { row ->
            nameLookup[row.name.lowercase()]?.let { enumMap[it] = row.id.toInt() }
        }

        // Add missing
        ContentType.entries.forEach { contentType ->
            if (contentType !in enumMap) {
                queries.insertContentType(contentType.name)
                enumMap[contentType] = queries.lastInsertId().executeAsOne().toInt()
            }
        }

        return enumMap
    }
}
