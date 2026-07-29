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
package org.bibletranslationtools.otter.common.initialization

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.ILanguageDataSource
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.languages.ImportLanguages
import javax.inject.Inject

const val LANGNAMES_PATH = "files/content/langnames.json"

class InitializeLanguages @Inject constructor(
    val installedEntityRepo: IInstalledEntityRepository,
    val languageRepo: ILanguageRepository,
    val languageDataSource: ILanguageDataSource,
    private val bundledContent: IBundledContentSource
) : Installable {

    override val name = "LANGUAGES"
    override val version = 2

    private val log = LoggerFactory.getLogger(InitializeLanguages::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        return Completable
            .fromCallable {
                val installedVersion = installedEntityRepo.getInstalledVersion(this)
                if (installedVersion != version) {
                    log.info("Initializing $name version: $version...")

                    migrate(installedVersion)

                    installedEntityRepo.install(this)
                    log.info("Languages imported!")
                    log.info("$name version: $version installed!")
                } else {
                    log.info("$name up to date with version: $version")
                }
            }
    }

    private fun migrate(fromVersion: Int?) {
        when (fromVersion) {
            1 -> migrate1to2()
            else -> {
                migrateTo1()
                migrate1to2()
            }
        }
    }

    private fun migrateTo1() {
        importLanguages()
            .doOnError { e ->
                log.error("Error importing languages.", e)
            }
            .blockingAwait()

    }

    private fun migrate1to2() {
        updateRegions()
            .doOnError { e ->
                log.error("Error updating regions.", e)
            }
            .blockingAwait()
    }

    private fun importLanguages(): Completable {
        val stream = bundledContent.readBlocking(LANGNAMES_PATH).inputStream()
        return ImportLanguages(languageRepo, languageDataSource)
            .import(stream)

    }

    private fun updateRegions(): Completable {
        val stream = bundledContent.readBlocking(LANGNAMES_PATH).inputStream()
        return ImportLanguages(languageRepo, languageDataSource)
            .updateRegions(stream)
    }
}
