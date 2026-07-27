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

import io.reactivex.Completable
import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.workbook.Translation

interface ILanguageRepository : IRepository<Language> {
    fun insert(language: Language): Single<Int>
    fun insertAll(languages: List<Language>): Single<List<Int>>
    fun upsertAll(languages: List<Language>): Completable
    fun updateRegions(languages: List<Language>): Completable
    fun getBySlug(slug: String): Single<Language>
    fun getGateway(): Single<List<Language>>
    fun getAvailableGatewaySources(): Single<List<Language>>
    fun getTargets(): Single<List<Language>>
    fun getTranslation(sourceLanguage: Language, targetLanguage: Language): Single<Translation>
    fun getAllTranslations(): Single<List<Translation>>
    fun insertTranslation(translation: Translation): Single<Int>
    fun updateTranslation(translation: Translation): Completable
    fun deleteTranslation(translation: Translation): Completable

    suspend fun insertSuspend(language: Language): Int
    suspend fun insertAllSuspend(languages: List<Language>): List<Int>
    suspend fun upsertAllSuspend(languages: List<Language>)
    suspend fun updateRegionsSuspend(languages: List<Language>)
    suspend fun getBySlugSuspend(slug: String): Language
    suspend fun getGatewaySuspend(): List<Language>
    suspend fun getAvailableGatewaySourcesSuspend(): List<Language>
    suspend fun getTargetsSuspend(): List<Language>
    suspend fun getTranslationSuspend(sourceLanguage: Language, targetLanguage: Language): Translation
    suspend fun getAllTranslationsSuspend(): List<Translation>
    suspend fun insertTranslationSuspend(translation: Translation): Int
    suspend fun updateTranslationSuspend(translation: Translation)
    suspend fun deleteTranslationSuspend(translation: Translation)
}
