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
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import java.util.EnumMap

class SqlDelightWorkbookTypeDao(
    private val db: AppDatabase
) {
    private val queries = db.appDatabaseQueries
    private val mapToId: Map<ProjectMode, Int> by lazy { loadDatabaseMap() }
    private val mapToEnum: Map<Int, ProjectMode> by lazy { mapToId.entries.associate { (k, v) -> v to k } }

    fun fetchId(mode: ProjectMode): Int {
        return mapToId[mode] ?: throw IllegalStateException("$mode is missing from WorkbookType table.")
    }

    fun fetchForId(databaseId: Int): ProjectMode? = mapToEnum[databaseId]

    private fun loadDatabaseMap(): EnumMap<ProjectMode, Int> {
        val enumMap = EnumMap<ProjectMode, Int>(ProjectMode::class.java)
        
        // Populate existing from DB
        queries.fetchAllWorkbookType().executeAsList().forEach { row ->
            ProjectMode.get(row.name)?.let { enumMap[it] = row.id.toInt() }
        }

        // Add missing
        ProjectMode.entries.forEach { mode ->
            if (mode !in enumMap) {
                queries.insertWorkbookType(mode.name)
                enumMap[mode] = queries.lastInsertId().executeAsOne().toInt()
            }
        }

        return enumMap
    }
}
