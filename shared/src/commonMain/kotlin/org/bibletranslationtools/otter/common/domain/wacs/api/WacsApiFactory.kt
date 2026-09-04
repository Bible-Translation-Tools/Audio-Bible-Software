/*
 * Copyright (C) 2020-2026 Wycliffe Associates
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
package org.bibletranslationtools.otter.common.domain.wacs.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * Builds a [ForgejoApi] for a given host. A factory rather than one fixed Koin-provided instance
 * because the M1 login screen lets the user type an arbitrary WACS host (pre-filled from
 * [org.bibletranslationtools.otter.common.domain.wacs.WacsConfig.baseUrl], but editable) — Retrofit
 * bakes its base URL in at construction, so each distinct host needs its own [Retrofit] built from
 * this factory. [http] (shared, carries the `X-Requested-With` interceptor + timeouts — see the
 * Koin module) is reused across hosts; only the base URL differs per call.
 *
 * Converter note: `com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0` looks
 * at first glance Java-only (`KotlinSerializationConverterFactory.create(...)` is the mangled JVM
 * name of the `StringFormat.asConverterFactory` extension), but the extension itself is a *public*
 * top-level function — only the `Factory` class it returns as `Converter.Factory` is `internal`,
 * which is a legal, ordinary "return a public supertype" pattern. So calling
 * `json.asConverterFactory(mediaType)` from Kotlin compiles fine with 1.0.0 as pinned; no version
 * bump or Java shim needed. (Confirmed both by decompiling the published jar — the extension is
 * `public final class ... { public static final Converter$Factory create(StringFormat, MediaType) }`
 * — and empirically: `LanguageDataSource.fetchEndpoint` in this same module already calls it this
 * way against the identical pinned version.)
 */
class WacsApiFactory(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun create(baseUrl: String): ForgejoApi {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(http)
            // MediaType.get, not the "…".toMediaType() extension: that is okhttp 4, and this
            // module is pinned to Retrofit 2.9.0 / okhttp 3.14.9 (see LanguageDataSource).
            .addConverterFactory(json.asConverterFactory(MediaType.get("application/json")))
            .build()
            .create(ForgejoApi::class.java)
    }
}
