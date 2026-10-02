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
package org.bibletranslationtools.otter.common.domain.project.importer

import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxMaybe
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.MediaMerge
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledSourceEditions
import org.bibletranslationtools.otter.common.domain.resourcecontainer.OtterResourceContainerConfig
import org.slf4j.LoggerFactory
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File

/**
 * Handles an incoming source that is an edition already installed: same language, identifier and
 * creator, and the same verse structure and text (see isSameEdition). Its media is merged into
 * the installed edition, and the installed edition's metadata (such as its version label) is
 * updated from the incoming manifest. Text and structure are never touched.
 *
 * Anything else, including a new edition of an installed source, is passed on to be installed
 * as its own edition beside the others. Nothing is deleted or merged into another edition's text.
 */
class ExistingSourceImporter(
    directoryProvider: ITempFileProvider,
    private val resourceMetadataRepository: IResourceMetadataRepository,
    private val installedEditions: InstalledSourceEditions,
    private val fingerprinter: EditionFingerprinter
) : RCImporter(directoryProvider, resourceMetadataRepository) {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    override fun import(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): Single<ImportResult> {
        return findSameEdition(file)
            .flatMap { installed ->
                callback?.onNotifyProgress(localizeKey = "importing_source_audio", percent = 50.0)
                logger.info("${file.name} is an installed edition (${installed.path}); merging its media")
                mergeIntoInstalledEdition(file, installed)
                    .doOnSuccess { result ->
                        if (result == ImportResult.SUCCESS) {
                            callback?.onNotifySuccess(language = installed.language.name)
                        } else {
                            callback?.onError(file.name)
                        }
                    }
                    .toMaybe()
            }
            .switchIfEmpty(Single.defer { passToNextImporter(file, callback, options) })
    }

    /**
     * The installed edition [file] is, if any. The file is only parsed when some edition of the
     * same source is installed; a file that can't be read is left to the next importer.
     */
    private fun findSameEdition(file: File) = rxMaybe(Dispatchers.IO) {
        val dublinCore = runCatching {
            ResourceContainer.load(file, OtterResourceContainerConfig()).use { it.manifest.dublinCore }
        }.getOrNull() ?: return@rxMaybe null
        val languageSlug = dublinCore.language.identifier
        if (installedEditions.editionsOf(languageSlug, dublinCore.identifier).isEmpty()) return@rxMaybe null

        val fingerprint = runCatching { fingerprinter.fingerprint(file) }
            .onFailure { logger.error("Could not fingerprint ${file.name}", it) }
            .getOrNull() ?: return@rxMaybe null
        installedEditions.findSameEdition(languageSlug, dublinCore.identifier, dublinCore.creator, fingerprint)
    }

    private fun mergeIntoInstalledEdition(file: File, installed: ResourceMetadata): Single<ImportResult> =
        Single
            .fromCallable {
                MediaMerge.merge(ResourceContainer.load(file), ResourceContainer.load(installed.path))
                ResourceContainer.load(file).use { incoming ->
                    resourceMetadataRepository.update(installed, incoming).blockingAwait()
                }
                ImportResult.SUCCESS
            }
            .onErrorReturn {
                logger.error("Merging ${file.name} into ${installed.path} failed", it)
                ImportResult.FAILED
            }
}
