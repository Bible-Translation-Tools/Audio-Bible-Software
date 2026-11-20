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

import org.bibletranslationtools.otter.common.domain.project.BundledSourceStamps
import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxCompletable
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportException
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File

const val EN_ULB_FILENAME = "en_ulb"
private const val EN_ULB_PATH = "files/content/$EN_ULB_FILENAME.zip"

class InitializeUlb(
    private val directoryProvider: ITempFileProvider,
    private val installedEntityRepo: IInstalledEntityRepository,
    private val importer: ImportProjectUseCase,
    private val bundledContent: IBundledContentSource,
    private val bundledStamps: BundledSourceStamps
) : Installable {

    override val name = "EN_ULB"
    override val version = 1

    private val log = LoggerFactory.getLogger(InitializeUlb::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable =
        rxCompletable { execSuspend(progressEmitter::onNext) }

    override suspend fun execSuspend(onProgress: (ProgressStatus) -> Unit) {
        withContext(Dispatchers.IO) {
            runCatching { initialize(onProgress) }
                .onFailure { log.error("Error in initializeUlb", it) }
                .getOrThrow()
        }
    }

    private suspend fun initialize(onProgress: (ProgressStatus) -> Unit) {
        val installedVersion = installedEntityRepo.getInstalledVersion(this)
        if (installedVersion == version) {
            log.info("$name up to date with version: $version")
            return
        }

        val enUlbFile = prepareImportFile()
        if (importer.isAlreadyImportedSuspend(enUlbFile)) {
            bundledStamps.markImported(EN_ULB_FILENAME)
            log.info("$EN_ULB_FILENAME already exists, skipped.")
            return
        }

        log.info("Initializing $name version: $version...")
        onProgress(
            ProgressStatus(
                titleKey = "initializingSources",
                subTitleKey = "loadingSomething",
                subTitleMessage = name
            )
        )
        val callback = setupImportCallback(onProgress)
        val result = runCatching { importer.importSuspend(enUlbFile, callback) }
            .onFailure { log.error("Error importing $EN_ULB_FILENAME.", it) }
            .getOrThrow()

        if (result == ImportResult.SUCCESS) {
            installedEntityRepo.install(this)
            bundledStamps.markImported(EN_ULB_FILENAME)
            log.info("$name version: $version installed!")
        } else {
            log.error(result.toString())
            throw ImportException(result)
        }
    }

    private suspend fun prepareImportFile(): File {
        val enUlbResource = bundledContent.read(EN_ULB_PATH).inputStream()
        val tempFile = directoryProvider.createTempFile("en_ulb-default", ".zip")
            .also(File::deleteOnExit)

        enUlbResource.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }
}
