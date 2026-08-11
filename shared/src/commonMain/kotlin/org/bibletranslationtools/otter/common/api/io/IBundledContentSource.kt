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
package org.bibletranslationtools.otter.common.api.io

/**
 * Reads content that ships bundled inside the application — the gateway-language ULB zips,
 * the langnames catalog, the GL source manifests, the ULB versification json.
 *
 * The backend needs these files but must not know how they are packaged. Today they are
 * Compose Multiplatform resources, so the only way to reach them is the generated `Res`
 * object; that is an adapter detail and it does not belong in `domain/` or
 * `initialization/`. Depending on this port instead keeps the UI framework out of the inner
 * layers and makes the call sites testable with a plain in-memory fake.
 *
 * Paths are the packaged resource paths, relative to the resources root and without any
 * `composeResources/` prefix (e.g. `files/content/en_ulb.zip`).
 */
interface IBundledContentSource {

    /**
     * Read the whole resource at [path].
     *
     * @throws Exception if the resource is absent or unreadable; callers that treat a missing
     *  bundle as a degraded-but-valid state are expected to wrap this in `runCatching`.
     */
    suspend fun read(path: String): ByteArray

    /**
     * Blocking [read], for the synchronous and RxJava call sites that cannot suspend.
     *
     * The blocking bridge lives here, in the adapter's contract, rather than as a
     * `runBlocking` scattered through the use cases. It should disappear once the Rx call
     * sites in `initialization/` and `domain/project/` become suspending.
     */
    fun readBlocking(path: String): ByteArray
}
