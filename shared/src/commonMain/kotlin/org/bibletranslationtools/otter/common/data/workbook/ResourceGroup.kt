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
package org.bibletranslationtools.otter.common.data.workbook

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

/** The resources (such as notes or questions) of one resource container for an element. */
class ResourceGroup internal constructor(
    val metadata: ResourceMetadata,
    private val load: suspend () -> List<Resource>
) {
    private val loading = Mutex()
    private var loaded: List<Resource>? = null

    /** The resources, read from storage the first time and kept. */
    suspend fun resources(): List<Resource> =
        loaded ?: loading.withLock { loaded ?: load().also { loaded = it } }
}
