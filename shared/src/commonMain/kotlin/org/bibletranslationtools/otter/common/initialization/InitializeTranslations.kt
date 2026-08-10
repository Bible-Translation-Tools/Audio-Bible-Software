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
@file:Suppress("FunctionNaming")
package org.bibletranslationtools.otter.common.initialization

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.data.workbook.Translation
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import java.time.LocalDateTime

class InitializeTranslations(
    private val installedEntityRepo: IInstalledEntityRepository,
    private val workbookRepository: IWorkbookRepository,
    private val languageRepository: ILanguageRepository
) : Installable {
    override val name = "TRANSLATIONS"
    override val version = 1

    private val log = LoggerFactory.getLogger(InitializeTranslations::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        return Completable.fromCallable {
            var installedVersion = installedEntityRepo.getInstalledVersion(this)
            if (installedVersion != version) {
                log.info("Initializing $name version: $version...")

                migrate()

                installedEntityRepo.install(this)
                log.info("$name version: $version installed!")
            } else {
                log.info("$name up to date with version: $version")
            }
        }
    }

    private fun migrate() {
        migrateToVersion1()
    }

    private fun migrateToVersion1() {
        val projects = fetchProjects()
        projects.map(::insertTranslation)
    }

    private fun fetchProjects(): List<Workbook> {
        return workbookRepository.getProjects()
            .doOnError { e ->
                log.error("Error in loading projects", e)
            }
            .blockingGet()
    }

    private fun insertTranslation(workBook: Workbook) {
        val translation = Translation(
            workBook.source.language,
            workBook.target.language,
            LocalDateTime.now()
        )
        languageRepository
            .insertTranslation(translation)
            .doOnError { e ->
                log.error("Error in inserting translation", e)
            }
            .onErrorReturnItem(0)
            .blockingGet()
    }
}
