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

import io.reactivex.Completable
import io.reactivex.schedulers.Schedulers
import org.bibletranslationtools.otter.common.api.persistence.ILanguageDataSource
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import java.io.InputStream
import org.bibletranslationtools.otter.common.OTTER_JSON
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable

// Imports from langnames.json
class ImportLanguages(
    private val languageRepo: ILanguageRepository,
    private val languageDataSource: ILanguageDataSource
    ) {

    private val logger = LoggerFactory.getLogger(ImportLanguages::class.java)

    fun import(inputStream: InputStream): Completable {
        return Completable
            .fromCallable {
                val languages = mapLanguages(inputStream)
                languageRepo.insertAll(languages).blockingGet()
            }
            .doOnError { e ->
                logger.error("Error in ImportLanguages", e)
            }
            .subscribeOn(Schedulers.io())
    }

    fun update(url: String): Completable {
        return languageDataSource.fetchLanguageNames(url)
            .flatMapCompletable {
                languageRepo.upsertAll(it)
            }
            .doOnError { e ->
                logger.error("Error in update languages", e)
            }
            .subscribeOn(Schedulers.io())
    }

    fun updateRegions(inputStream: InputStream): Completable {
        return Completable
            .fromCallable {
                val languages = mapLanguages(inputStream)
                languageRepo.updateRegions(languages).blockingGet()
            }
            .doOnError { e ->
                logger.error("Error in updateRegions", e)
            }
            .subscribeOn(Schedulers.io())
    }

    private fun mapLanguages(inputStream: InputStream): List<Language> {
        val languages = inputStream.bufferedReader().use {
            OTTER_JSON.decodeFromString(ListSerializer(Door43Language.serializer()), it.readText())
        }
        return languages.map { it.toLanguage() }
    }
}

@Serializable
private data class Door43Language(
    val pk: Int, // id
    val lc: String, // slug
    val ln: String, // name
    val ld: String, // direction
    val gw: Boolean, // isGateway
    val ang: String, // anglicizedName
    val lr: String // region
) {
    fun toLanguage(): Language = Language(lc, ln, ang, ld, gw, lr)
}
