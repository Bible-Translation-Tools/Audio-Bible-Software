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
import org.bibletranslationtools.otter.common.api.persistence.IResourceContainerDirectories
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.OratureFileFormat
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.data.ProgressStatus
import java.io.File

class InitializeSources(
    private val directoryProvider: IResourceContainerDirectories,
    private val resourceMetadataRepo: IResourceMetadataRepository,
    private val installedEntityRepo: IInstalledEntityRepository,
    private val rcImporterFactory: RCImporterFactory
): Installable {

    override val name = "SOURCES"
    override val version = 1

    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var callback: ProjectImporterCallback

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        return Completable
            .fromAction {
                val installedVersion = installedEntityRepo.getInstalledVersion(this)
                if (installedVersion != version) {
                    logger.info("Initializing sources...")
                    progressEmitter.onNext(
                        ProgressStatus(titleKey = "initializingSources")
                    )
                    callback = setupImportCallback(progressEmitter)

                    migrate()

                    installedEntityRepo.install(this)
                    logger.info("$name version: $version installed!")
                } else {
                    logger.info("$name up to date with version: $version")
                }
            }
    }

    private fun migrate() {
        migrateToVersion1()
    }

    private fun migrateToVersion1() {
        importSources(directoryProvider.internalSourceRCDirectory)
    }

    private fun importSources(dir: File) {
        if (dir.isFile || !dir.exists()) {
            return
        }

        val existingPaths = fetchSourcePaths()

        dir.walk().filter {
            it.isFile && it !in existingPaths
        }.forEach {
            // Find resource containers to import
            if (it.extension in OratureFileFormat.extensionList) {
                importFile(it)
            }
        }
    }

    private fun fetchSourcePaths(): List<File> {
        return resourceMetadataRepo
            .getAllSources()
            .blockingGet()
            .map {
                it.path
            }
    }

    private fun importFile(file: File) {
        rcImporterFactory.makeImporter().import(file, callback).toObservable()
            .doOnError { e ->
                logger.error("Error importing $file.", e)
            }
            .blockingSubscribe {
                logger.info("${file.name} imported!")
            }
    }
}