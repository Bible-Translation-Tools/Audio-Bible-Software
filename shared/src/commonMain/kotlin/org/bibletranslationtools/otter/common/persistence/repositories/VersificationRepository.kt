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
package org.bibletranslationtools.otter.common.persistence.repositories

import io.reactivex.Maybe
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxMaybe
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.OTTER_JSON
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.bibletranslationtools.otter.common.domain.versification.ParatextVersification
import org.bibletranslationtools.otter.common.domain.versification.Versification
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.slf4j.LoggerFactory
import java.io.File

class VersificationRepository(
    database: DaoProvider,
    private val directoryProvider: IAppDirectories
) : IVersificationRepository {

    private val logger = LoggerFactory.getLogger(LanguageRepository::class.java)

    private val versificationDao = database.versificationDao

    override fun getVersification(slug: String): Maybe<Versification> {
        return rxMaybe { getVersificationSuspend(slug) }
            .subscribeOn(Schedulers.io())
    }

    override suspend fun getVersificationSuspend(slug: String): Versification? =
        withContext(Dispatchers.IO) {
            directoryProvider.versificationDirectory.mkdirs()
            versificationDao.fetchVersificationFile(slug)?.let { vrsFileName ->
                val vrsFile = File(directoryProvider.versificationDirectory, vrsFileName)
                OTTER_JSON.decodeFromString(
                    deserializer = ParatextVersification.serializer(),
                    string = vrsFile.readText()
                )
            }
        }
    override suspend fun insertVersificationSuspend(slug: String, path: File) {
        withContext(Dispatchers.IO) { versificationDao.upsert(slug, path.name) }
    }
}