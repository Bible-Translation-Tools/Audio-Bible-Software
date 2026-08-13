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
package org.bibletranslationtools.otter.common.domain.languages

import io.reactivex.Observable
import org.bibletranslationtools.otter.common.api.persistence.ILanguageDataSource
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.api.persistence.LanguagesApi
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType
import java.io.File
import org.bibletranslationtools.otter.common.OTTER_JSON
import kotlinx.serialization.builtins.ListSerializer

class LanguageDataSource() : ILanguageDataSource {
    override fun fetchLanguageNames(url: String): Observable<List<Language>> {
        return if (File(url).exists()) {
            fetchLocalFile(url)
        } else {
            fetchEndpoint(url)
        }
    }

    private fun fetchLocalFile(path: String): Observable<List<Language>> {
        return Observable
            .fromCallable {
                OTTER_JSON.decodeFromString(
                    ListSerializer(Language.serializer()), File(path).readText()
                )
            }
    }

    private fun fetchEndpoint(url: String): Observable<List<Language>> {
        // Using localhost as a base url is a workaround, because retrofit always requires base url to be set,
        // even for full dynamic urls like in this case.
        // When retrofit sees that base url and target url are different (scheme, domain),
        // it will use the latter

        val request = Retrofit.Builder()
            .baseUrl("http://localhost")
            .addConverterFactory(// MediaType.get, not the "…".toMediaType() extension: that is okhttp 4, and
            // Retrofit 2.9.0 brings okhttp 3.14.9.
            OTTER_JSON.asConverterFactory(MediaType.get("application/json")))
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .build()
            .create(LanguagesApi::class.java)

        return request.fetchLanguages(url)
    }
}