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

import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.persistence.database.dao.CheckingStatusDao
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [CheckingStatusDao]. Mirrors the jOOQ CheckingStatusDao's stateful lazy seeding:
 * the enum→id map is built once from the existing rows (matched via [CheckingStatus.get]), and any
 * enum value not yet present is inserted and added to the map.
 */
internal class SqlDelightCheckingStatusDao(private val db: OtterDatabase) : CheckingStatusDao {
    private val queries = db.checkingStatusQueries

    private val mapToId: Map<CheckingStatus, Int> by lazy { loadToDatabase() }
    private val mapToEnum: Map<Int, CheckingStatus> by lazy { mapToId.entries.associate { (k, v) -> v to k } }

    override fun fetchId(mode: CheckingStatus): Int {
        return mapToId[mode]
            ?: throw IllegalStateException("Mode: $mode does not exist in database table.")
    }

    override fun fetchById(databaseId: Int): CheckingStatus? = mapToEnum[databaseId]

    private fun loadToDatabase(): Map<CheckingStatus, Int> {
        val enumMap = LinkedHashMap<CheckingStatus, Int>()
        enumMap.putAll(getAll())

        CheckingStatus.values()
            .filterNot { it in enumMap } // exclude existing items
            .forEach { enumMap[it] = insert(it) } // insert new items to db

        return enumMap
    }

    private fun insert(checkingStatus: CheckingStatus): Int {
        return db.transactionWithResult {
            queries.insert(checkingStatus.name)
            queries.selectMaxId().executeAsOne().max!!
        }
    }

    private fun getAll(): Map<CheckingStatus, Int> {
        return queries.selectAll().executeAsList()
            .mapNotNull { row ->
                CheckingStatus.get(row.name)?.let { Pair(it, row.id) }
            }
            .associate { it }
    }
}
