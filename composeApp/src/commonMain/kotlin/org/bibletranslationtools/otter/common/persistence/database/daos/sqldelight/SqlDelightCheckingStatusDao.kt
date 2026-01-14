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
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import java.util.EnumMap

class SqlDelightCheckingStatusDao(
    private val db: AppDatabase
) {
    private val queries = db.appDatabaseQueries
    private val mapToId: Map<CheckingStatus, Int> by lazy { loadDatabaseMap() }
    private val mapToEnum: Map<Int, CheckingStatus> by lazy { mapToId.entries.associate { (k, v) -> v to k } }

    fun fetchId(status: CheckingStatus): Int {
        return mapToId[status] ?: throw IllegalStateException("$status is missing from CheckingStatus table.")
    }

    fun fetchForId(databaseId: Int): CheckingStatus? = mapToEnum[databaseId]

    private fun loadDatabaseMap(): EnumMap<CheckingStatus, Int> {
        val enumMap = EnumMap<CheckingStatus, Int>(CheckingStatus::class.java)
        
        // Populate existing from DB
        queries.fetchAllCheckingStatus().executeAsList().forEach { row ->
            CheckingStatus.get(row.name)?.let { enumMap[it] = row.id.toInt() }
        }

        // Add missing
        CheckingStatus.entries.forEach { status ->
            if (status !in enumMap) {
                queries.insertCheckingStatus(status.name)
                enumMap[status] = queries.lastInsertId().executeAsOne().toInt()
            }
        }

        return enumMap
    }
}
