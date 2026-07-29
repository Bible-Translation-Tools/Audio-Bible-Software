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
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.config.Initializable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.ProgressStatus
import java.io.File
import javax.inject.Inject

private const val ULB_VERSIFICATION_FILE = "ulb.json"
private const val UFW_VERSIFICATION_FILE = "ufw.json"
private const val ULB_VERSIFICATION_RESOURCE_PATH = "files/versification/ulb_versification.json"

class InitializeVersification @Inject constructor(
    val directoryProvider: IDirectoryProvider,
    val versificationRepository: IVersificationRepository,
    private val bundledContent: IBundledContentSource
) : Initializable {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        return Single.fromCallable {
            progressEmitter.onNext(ProgressStatus(titleKey = "initializingVersification"))
            copyUlbVersification()

            directoryProvider.versificationDirectory.listFiles()?.forEach { file ->
                if (file.extension == "json") {
                    logger.info("Inserting versification: ${file.name}")
                    versificationRepository.insertVersification(file.nameWithoutExtension, file)
                        .blockingAwait()
                }
            }
        }.subscribeOn(Schedulers.io())
            .ignoreElement()
    }

    private fun copyUlbVersification() {
        directoryProvider.versificationDirectory.mkdirs()
        logger.info("Copying ulb versification")
        // Read through the bundled-content port, NOT ClassLoader.getSystemResourceAsStream:
        // this file is packaged as a Compose Multiplatform resource, so a JVM classpath lookup
        // returns null for it (the old code's ?.use{} then silently did nothing, leaving
        // versification uninitialized).
        val bytes = runCatching { bundledContent.readBlocking(ULB_VERSIFICATION_RESOURCE_PATH) }
            .getOrElse {
                logger.error("Failed to read bundled versification resource $ULB_VERSIFICATION_RESOURCE_PATH", it)
                return
            }
        listOf(ULB_VERSIFICATION_FILE, UFW_VERSIFICATION_FILE).forEach { fileName ->
            File(directoryProvider.versificationDirectory.absolutePath, fileName)
                .outputStream()
                .use { it.write(bytes) }
        }
    }
}
