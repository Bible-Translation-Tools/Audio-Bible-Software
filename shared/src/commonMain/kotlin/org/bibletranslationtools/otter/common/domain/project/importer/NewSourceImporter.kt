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
import io.reactivex.schedulers.Schedulers
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportException
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.OtterResourceContainerConfig
import org.bibletranslationtools.otter.common.domain.resourcecontainer.castOrFindImportException
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IProjectReader
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.VersificationTreeBuilder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.toCollection
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceContainerRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File
import java.io.IOException
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository

/**
 * Which tree a source import writes, and whether the parsed text still has to be applied on top.
 *
 * @param treeToImport what [IResourceContainerRepository.importResourceContainer] receives
 * @param applyParsedTextAfter whether the imported structure still needs the source text filled in
 */
internal data class ImportPlan(
    val treeToImport: OtterTree<CollectionOrContent>,
    val applyParsedTextAfter: Boolean
)

/**
 * Decides what a source resource container import should write.
 *
 * With a versification available, the structure comes from the *versification* — every chapter and
 * verse it declares, seeded onto [containerCollection] — and the source text is applied afterwards.
 * That is what makes a verse the source text happens to omit still recordable. Without one, the only
 * structure available is what the text itself contains.
 *
 * Split out from [NewSourceImporter.importContainer] because reaching this decision through the
 * importer requires a real resource container on disk, and the decision is the part worth pinning.
 */
internal fun planImport(
    containerCollection: CollectionOrContent,
    parsedTree: OtterTree<CollectionOrContent>,
    versificationTrees: List<OtterTree<CollectionOrContent>>?
): ImportPlan {
    if (versificationTrees.isNullOrEmpty()) {
        return ImportPlan(treeToImport = parsedTree, applyParsedTextAfter = false)
    }
    val preallocation = OtterTree<CollectionOrContent>(containerCollection)
    versificationTrees.forEach { preallocation.addChild(it) }
    return ImportPlan(treeToImport = preallocation, applyParsedTextAfter = true)
}

class NewSourceImporter(
    private val directoryProvider: IDirectoryProvider,
    private val resourceContainerRepository: IResourceContainerRepository,
    resourceMetadataRepository: IResourceMetadataRepository,
    private val versificationTreeBuilder: VersificationTreeBuilder,
    // Kept alongside the tree builder: getVersification() below reads "ulb" directly to synthesize
    // USFM for audio-only containers, which the tree builder has no entry point for.
    private val versificationRepository: IVersificationRepository,
    private val zipEntryTreeBuilder: IZipEntryTreeBuilder
) : RCImporter(directoryProvider, resourceMetadataRepository) {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private var sourceLanguageName = ""
    private var projectSlug: String? = null

    override fun import(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): Single<ImportResult> {
        return importContainer(file, callback)
    }

    private fun importContainer(
        file: File,
        callback: ProjectImporterCallback?
    ): Single<ImportResult> {
        return Single.create<ImportResult> { emitter ->
            logger.info("Importing RC...")
            callback?.onNotifyProgress(
                localizeKey = "loadingSomething", message = "${file.name}", percent = 10.0
            )
            val fileToImport = prepareFileToImport(file)

            val container = try {
                val rc = ResourceContainer.load(fileToImport, OtterResourceContainerConfig())
                rc.also {
                    sourceLanguageName = it.manifest.dublinCore.language.title
                    projectSlug = it.media?.projects?.singleOrNull()?.identifier
                }

                if (rc.manifest.projects.isEmpty()) {
                    val booksInMedia = rc.media?.projects?.map { it.identifier } ?: emptyList()
                    if (booksInMedia.isNotEmpty()) {
                        val versification = getVersification(fileToImport)
                        if (versification != null) {
                            booksInMedia.forEach { bookSlug ->
                                val usfmContent = generateUsfmContent(bookSlug, versification)
                                val usfmFile = File(fileToImport, "$bookSlug.usfm")
                                usfmFile.writeText(usfmContent)

                                (rc.manifest.projects as MutableList).add(
                                    org.wycliffeassociates.resourcecontainer.entity.Project(
                                        title = bookSlug,
                                        versification = "ulb",
                                        identifier = bookSlug,
                                        sort = 0,
                                        path = "./${usfmFile.name}",
                                        categories = listOf()
                                    )
                                )
                            }
                            // Re-write manifest to include new projects
                            rc.writeManifest()
                        }
                    }
                }
                rc
            } catch (e: Exception) {
                logger.error("Error loading rc in importFromInternalDir, file: $fileToImport", e)
                cleanUp(fileToImport, ImportResult.LOAD_RC_ERROR).subscribe(emitter::onSuccess)
                return@create
            }

            val tree = try {
                IProjectReader.constructContainerTree(container, zipEntryTreeBuilder)
            } catch (e: ImportException) {
                logger.error("Error constructing container tree, file: $fileToImport", e)
                logger.error("Container had format: ${container.manifest.dublinCore.format}")
                container.close()
                cleanUp(fileToImport, e.result).subscribe(emitter::onSuccess)
                return@create
            }

            callback?.onNotifyProgress(
                localizeKey = "importingSource", percent = 50.0
            )

            // A versification problem must not fail the import: the tree builder reaches the
            // bundled file through a blockingGet() that throws rather than returning empty when it
            // is missing or malformed. Degrading to a text-only import is what the app did for the
            // whole period this path was disabled, so it is a known-good fallback.
            val versificationTrees = runCatching { versificationTreeBuilder.build(container) }
                .getOrElse {
                    logger.error(
                        "Could not build the versification tree for ${file.name}; " +
                            "importing from the source text only",
                        it
                    )
                    null
                }

            val plan = planImport(container.toCollection(), tree, versificationTrees)

            importTree(container, plan.treeToImport, fileToImport)
                .flatMap { result ->
                    // Only backfill text into a structure that actually imported. Chaining this
                    // unconditionally lets a failed import fall through into updateContent, where a
                    // second failure replaces the first and hides what actually went wrong.
                    if (plan.applyParsedTextAfter && result == ImportResult.SUCCESS) {
                        updateContentFromTextContent(container, tree)
                    } else {
                        Single.just(result)
                    }
                }
                .subscribe { result ->
                    notifyCallback(result, callback, file)
                    emitter.onSuccess(result)
                }
        }.onErrorReturn { e ->
            logger.error("Error in importContainer, file: $file", e)
            e.castOrFindImportException()?.result ?: throw e
        }.subscribeOn(Schedulers.io())
    }

    private fun notifyCallback(
        result: ImportResult?,
        callback: ProjectImporterCallback?,
        file: File
    ) {
        if (result == ImportResult.SUCCESS) {
            callback?.onNotifySuccess(language = sourceLanguageName, project = projectSlug)
        } else {
            callback?.onError(file.name)
        }
    }

    private fun prepareFileToImport(file: File): File {
        var exists = false
        val internalDir = getInternalDirectory(file) ?: throw ImportException(ImportResult.LOAD_RC_ERROR)
        if (internalDir.exists()) {
            val rcFileExists = file.isFile && internalDir.contains(file.name)
            val rcDirExists = file.isDirectory && internalDir.listFiles().isNotEmpty()
            if (rcFileExists || rcDirExists) {
                exists = true
            }
        }
        return if (exists) {
            file
        } else {
            copyToInternalDirectory(file, internalDir)
        }
    }

    private fun getInternalDirectory(file: File): File? {
        // Load the external container to get the metadata we need to figure out where to copy to
        val extContainer = try {
            ResourceContainer.load(file, OtterResourceContainerConfig())
        } catch (e: Exception) {
            // Could be checked or unchecked exception from RC library
            logger.error("Error in getInternalDirectory, file: $file", e)
            return null
        }
        return directoryProvider.getSourceContainerDirectory(extContainer)
    }

    private fun cleanUp(container: File, result: ImportResult): Single<ImportResult> = Single.fromCallable {
        container.deleteRecursively()
        return@fromCallable result
    }

    private fun importTree(
        container: ResourceContainer,
        tree: OtterTree<CollectionOrContent>,
        fileToLoad: File
    ): Single<ImportResult> {
        return resourceContainerRepository
            .importResourceContainer(container, tree, container.manifest.dublinCore.language.identifier)
            .doOnEvent { result, err ->
                if (err != null) {
                    logger.error("Error in importFromInternalDirectory importing rc, file: $fileToLoad", err)
                }
                if (result != ImportResult.SUCCESS || err != null) fileToLoad.deleteRecursively()
            }
    }

    private fun updateContentFromTextContent(
        container: ResourceContainer,
        tree: OtterTree<CollectionOrContent>
    ): Single<ImportResult> {
        return resourceContainerRepository
            .updateContent(
                container,
                tree
            )
    }

    private fun copyToInternalDirectory(file: File, destinationDirectory: File): File {
        return if (file.isDirectory) {
            copyRecursivelyToInternalDirectory(file, destinationDirectory)
        } else {
            extractSourceToDir(file, destinationDirectory)
        }
    }

    private fun copyRecursivelyToInternalDirectory(filepath: File, destinationDirectory: File): File {
        // Copy the resource container into the correct directory
        if (filepath.absoluteFile != destinationDirectory) {
            val success = filepath.copyRecursively(destinationDirectory, true)
            if (!success) {
                throw IOException("Could not copy resource container ${filepath.name} to resource container directory")
            }
        }
        return destinationDirectory
    }

    private fun File.contains(name: String): Boolean {
        if (!this.isDirectory) {
            throw Exception("Cannot call contains on non-directory file")
        }
        return this.listFiles().map { it.name }.contains(name)
    }

    private fun copyFileToInternalDirectory(filepath: File, destinationDirectory: File): File {
        // Copy the resource container zip file into the correct directory
        val destinationFile = File(destinationDirectory, filepath.name)
        if (filepath.absoluteFile != destinationFile) {
            filepath.copyTo(destinationFile, true)
            val success = destinationDirectory.contains(filepath.name)
            if (!success) {
                throw IOException("Could not copy resource container ${filepath.name} to resource container directory")
            }
        }
        return destinationFile
    }

    private fun extractSourceToDir(source: File, dir: File): File {
        val targetDir = dir.resolve(source.nameWithoutExtension)
        directoryProvider
            .newFileReader(source)
            .use { fileReader ->
                fileReader.copyDirectory("/", targetDir)
            }

        targetDir.walk().forEach {
            if (it.isDirectory && it.resolve("manifest.yaml").exists()) {
                return it
            }
        }

        return targetDir
    }

    private fun getVersification(rcDir: File): org.bibletranslationtools.otter.common.domain.versification.Versification? {
        // Try to find versification.json in the container
        val versificationFile = File(rcDir, "ingredients/versification.json")
        if (versificationFile.exists()) {
            try {
                val mapper = com.fasterxml.jackson.databind.ObjectMapper().registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule())
                return mapper.readValue(versificationFile, org.bibletranslationtools.otter.common.domain.versification.ParatextVersification::class.java)
            } catch (e: Exception) {
                logger.error("Failed to parse versification.json", e)
            }
        }
        // Fallback to default
        return versificationRepository.getVersification("ulb").blockingGet()
    }

    private fun generateUsfmContent(bookSlug: String, versification: org.bibletranslationtools.otter.common.domain.versification.Versification): String {
        val sb = StringBuilder()
        sb.append("\\id ${bookSlug.uppercase(java.util.Locale.US)}\n")

        val chapterCount = versification.getChaptersInBook(bookSlug)
        for (chapter in 1..chapterCount) {
            sb.append("\\c $chapter\n")
            sb.append("\\p\n")
            val verseCount = versification.getVersesInChapter(bookSlug, chapter)
            for (verse in 1..verseCount) {
                sb.append("\\v $verse \n")
            }
        }
        return sb.toString()
    }
}
