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
package org.bibletranslationtools.otter.common.domain.project

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource

/**
 * The catalog of gateway-language sources that ship with the app.
 *
 * Was a pair of `by lazy` properties on `ImportProjectUseCase.Companion`. A companion object
 * cannot be constructor-injected, so those lazies had to reach the bundled content through a
 * hard dependency on the Compose `Res` object — which is what put a UI framework inside
 * `domain/`. Extracting them into an injectable holder lets both read through
 * [IBundledContentSource] instead.
 *
 * Register as a singleton: the parsing is lazy and cached, matching the process-wide caching
 * the companion lazies used to give.
 */
class GlSourceCatalog(
    private val bundledContent: IBundledContentSource
) {

    /**
     * Every source in the shipped catalog ([SOURCES_JSON_FILE]).
     *
     * A missing or unparseable manifest degrades to "no embedded GL sources" rather than
     * crashing every caller (project wizard, sideloadSource, import).
     */
    val sources: List<ResourceInfoSerializable> by lazy {
        val bytes = runCatching { bundledContent.readBlocking(SOURCES_JSON_FILE) }
            .getOrNull() ?: return@lazy emptyList()
        runCatching { mapper().readValue<List<ResourceInfoSerializable>>(bytes) }
            .getOrDefault(emptyList())
    }

    /**
     * The source names (matching [ResourceInfoSerializable.name]) whose zip actually got
     * bundled, per the build-generated [EMBEDDED_SOURCES_FILE].
     *
     * The shipped catalog is partly stale — around a quarter of its URLs 404 — so the build
     * emits a manifest of what really landed in `files/content/`. Empty if that manifest is
     * absent (e.g. the download task never ran), which fails closed: no embedded sources
     * offered, rather than offering ones that cannot be sideloaded.
     */
    val embeddedSourceNames: Set<String> by lazy {
        val bytes = runCatching { bundledContent.readBlocking(EMBEDDED_SOURCES_FILE) }
            .getOrNull() ?: return@lazy emptySet()
        runCatching { mapper().readValue<List<String>>(bytes).toSet() }
            .getOrDefault(emptySet())
    }

    private fun mapper() = ObjectMapper(JsonFactory()).registerKotlinModule()
}
