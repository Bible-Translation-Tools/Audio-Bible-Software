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
package org.bibletranslationtools.otter.common.api.persistence.repositories

import io.reactivex.Single
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DeleteResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.wycliffeassociates.resourcecontainer.ResourceContainer

interface IResourceContainerRepository {
    /**
     * @param fingerprint stored with the new source row in the same transaction, when given.
     */
    fun importResourceContainer(
        rc: ResourceContainer,
        rcTree: OtterTree<CollectionOrContent>,
        languageSlug: String,
        fingerprint: EditionFingerprint? = null
    ): Single<ImportResult>

    fun removeResourceContainer(
        resourceContainer: ResourceContainer
    ): Single<DeleteResult>

    suspend fun importResourceContainerSuspend(
        rc: ResourceContainer,
        rcTree: OtterTree<CollectionOrContent>,
        languageSlug: String,
        fingerprint: EditionFingerprint? = null
    ): ImportResult

    suspend fun removeResourceContainerSuspend(
        resourceContainer: ResourceContainer
    ): DeleteResult
}
