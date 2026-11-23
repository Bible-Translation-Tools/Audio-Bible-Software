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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.IResourceContainerDirectories
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.OratureFileFormat
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.slf4j.LoggerFactory
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

    override suspend fun exec(onProgress: (ProgressStatus) -> Unit) {
        withContext(Dispatchers.IO) {
            val installedVersion = installedEntityRepo.getInstalledVersion(this@InitializeSources)
            if (installedVersion != version) {
                logger.info("Initializing sources...")
                onProgress(
                    ProgressStatus(titleKey = "initializingSources")
                )
                callback = setupImportCallback(onProgress)

                migrate()

                installedEntityRepo.install(this@InitializeSources)
                logger.info("$name version: $version installed!")
            } else {
                logger.info("$name up to date with version: $version")
            }
        }
    }

    private suspend fun migrate() {
        migrateToVersion1()
    }

    private suspend fun migrateToVersion1() {
        importSources(directoryProvider.internalSourceRCDirectory)
    }

    private suspend fun importSources(dir: File) {
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

    private suspend fun fetchSourcePaths(): List<File> {
        return resourceMetadataRepo
            .getAllSourcesNoRx()
            .map {
                it.path
            }
    }

    private suspend fun importFile(file: File) {
        runCatching { rcImporterFactory.makeImporter().importSuspend(file, callback) }
            .onSuccess { logger.info("${file.name} import result: $it") }
            .onFailure { logger.error("Error importing $file.", it) }
    }
}