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
    private val bundledContent: IBundledContentSource
) : Installable {

    override val name = "EN_ULB"
    override val version = 1

    private val log = LoggerFactory.getLogger(InitializeUlb::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        return Completable
            .fromCallable {
                val installedVersion = installedEntityRepo.getInstalledVersion(this)
                if (installedVersion != version) {
                    val enUlbFile = prepareImportFile()
                    if (importer.isAlreadyImported(enUlbFile)) {
                        log.info("$EN_ULB_FILENAME already exists, skipped.")
                        return@fromCallable Completable.complete()
                    }

                    log.info("Initializing $name version: $version...")
                    progressEmitter.onNext(
                        ProgressStatus(
                            titleKey = "initializingSources",
                            subTitleKey = "loadingSomething",
                            subTitleMessage = name
                        )
                    )
                    val callback = setupImportCallback(progressEmitter)
                    importer
                        .import(enUlbFile, callback)
                        .toObservable()
                        .doOnError { e ->
                            log.error("Error importing $EN_ULB_FILENAME.", e)
                        }
                        .blockingSubscribe { result ->
                            if (result == ImportResult.SUCCESS) {
                                installedEntityRepo.install(this)
                                log.info("$name version: $version installed!")
                            } else {
                                log.error(result.toString())
                                throw ImportException(result)
                            }
                        }
                } else {
                    log.info("$name up to date with version: $version")
                }
            }
            .doOnError { e ->
                log.error("Error in initializeUlb", e)
            }
    }

    private fun prepareImportFile(): File {
        val enUlbResource = bundledContent.readBlocking(EN_ULB_PATH).inputStream()
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
