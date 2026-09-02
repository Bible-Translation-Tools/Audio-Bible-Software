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

import org.bibletranslationtools.otter.common.persistence.database.dao.SubtreeHasResourceDao
import org.bibletranslationtools.otter.db.OtterDatabase

/**
 * SQLDelight-backed [SubtreeHasResourceDao]. Behavior mirrors the jOOQ SubtreeHasResourceDao,
 * returning the number of affected rows from each insert (`QueryResult<Long>.value`) and summing
 * them across a batched, transactional insert.
 */
internal class SqlDelightSubtreeHasResourceDao(private val db: OtterDatabase) : SubtreeHasResourceDao {
    private val queries = db.subtreeHasResourceQueries

    override fun insert(collectionId: Int, dublinCoreId: Int): Int =
        queries.insert(collectionId, dublinCoreId).value.toInt()

    override fun insert(collectionIdsToDublinCoreIds: Sequence<Pair<Int, Int>>): Int =
        db.transactionWithResult {
            collectionIdsToDublinCoreIds.sumOf { (collectionId, dublinCoreId) ->
                queries.insert(collectionId, dublinCoreId).value.toInt()
            }
        }

    override fun fetchDublinCoreIdsByCollectionId(id: Int): List<Int> =
        queries.fetchDublinCoreIdsByCollectionId(id).executeAsList()
}
