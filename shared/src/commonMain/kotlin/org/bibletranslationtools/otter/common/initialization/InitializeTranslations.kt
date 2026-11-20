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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxCompletable
import kotlinx.coroutines.withContext
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

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable =
        rxCompletable { execSuspend(progressEmitter::onNext) }

    override suspend fun execSuspend(onProgress: (ProgressStatus) -> Unit) {
        withContext(Dispatchers.IO) {
            val installedVersion = installedEntityRepo.getInstalledVersion(this@InitializeTranslations)
            if (installedVersion != version) {
                log.info("Initializing $name version: $version...")

                migrate()

                installedEntityRepo.install(this@InitializeTranslations)
                log.info("$name version: $version installed!")
            } else {
                log.info("$name up to date with version: $version")
            }
        }
    }

    private suspend fun migrate() {
        migrateToVersion1()
    }

    private suspend fun migrateToVersion1() {
        val projects = fetchProjects()
        projects.forEach { insertTranslation(it) }
    }

    private suspend fun fetchProjects(): List<Workbook> =
        runCatching { workbookRepository.getProjectsSuspend() }
            .onFailure { log.error("Error in loading projects", it) }
            .getOrThrow()

    private suspend fun insertTranslation(workBook: Workbook) {
        val translation = Translation(
            workBook.source.language,
            workBook.target.language,
            LocalDateTime.now()
        )
        runCatching { languageRepository.insertTranslationSuspend(translation) }
            .onFailure { log.error("Error in inserting translation", it) }
    }
}
