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
import kotlinx.coroutines.rx2.rxSingle
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.OTTER_JSON
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceContainerRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportException
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.OtterResourceContainerConfig
import org.bibletranslationtools.otter.common.domain.resourcecontainer.castOrFindImportException
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IProjectReader
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.bibletranslationtools.otter.common.domain.versification.ParatextVersification
import org.bibletranslationtools.otter.common.domain.versification.StandardVersifications
import org.bibletranslationtools.otter.common.domain.versification.Versification
import org.slf4j.LoggerFactory
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File
import java.io.IOException
import java.util.UUID

class NewSourceImporter(
    private val directoryProvider: IDirectoryProvider,
    private val resourceContainerRepository: IResourceContainerRepository,
    private val metadataRepository: IResourceMetadataRepository,
    private val structurePlanner: SourceStructurePlanner,
    private val fingerprinter: EditionFingerprinter,
    // getVersification() below reads a versification directly to synthesize USFM for audio-only
    // containers.
    private val versificationRepository: IVersificationRepository,
    private val zipEntryTreeBuilder: IZipEntryTreeBuilder
) : RCImporter(directoryProvider, metadataRepository) {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private var sourceLanguageName = ""
    private var projectSlug: String? = null

    override fun import(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): Single<ImportResult> = rxSingle { importSuspend(file, callback, options) }

    override suspend fun importSuspend(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): ImportResult =
        runCatching { withContext(Dispatchers.IO) { importContainer(file, callback) } }
            .onFailure { logger.error("Error in importContainer, file: $file", it) }
            .getOrElse { it.castOrFindImportException()?.result ?: throw it }

    private suspend fun importContainer(
        file: File,
        callback: ProjectImporterCallback?
    ): ImportResult {
        logger.info("Importing RC...")
        callback?.onNotifyProgress(
            localizeKey = "loadingSomething", message = "${file.name}", percent = 10.0
        )
        val staged = try {
            stage(file)
        } catch (e: Exception) {
            logger.error("Could not unpack ${file.name}", e)
            return ImportResult.LOAD_RC_ERROR
        }
        val fileToImport = staged.rcFile

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
                                    // A code the versification table knows, so later lookups by
                                    // the manifest's versification resolve.
                                    versification = StandardVersifications.DEFAULT,
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
            return staged.discard(ImportResult.LOAD_RC_ERROR)
        }

        val tree = try {
            IProjectReader.constructContainerTree(container, zipEntryTreeBuilder)
        } catch (e: ImportException) {
            logger.error("Error constructing container tree, file: $fileToImport", e)
            logger.error("Container had format: ${container.manifest.dublinCore.format}")
            container.close()
            return staged.discard(e.result)
        }

        callback?.onNotifyProgress(
            localizeKey = "importingSource", percent = 50.0
        )

        // A versification problem must not fail the import. Importing the parsed text alone is
        // what the app did before gap-filling existed, so it is a known-good fallback.
        val plan = runCatching { structurePlanner.plan(container, tree) }
            .getOrElse {
                logger.error(
                    "Could not plan the structure for ${file.name}; importing the source text as parsed",
                    it
                )
                SourceStructurePlan(tree, null)
            }
        // Taken from the parsed text, not the gap-filled tree: the fingerprint describes the
        // edition's own content, independent of what the app adds to make it recordable.
        val fingerprint = runCatching {
            fingerprinter.fingerprint(
                parsedTree = tree,
                detectedVersification = plan.match?.code
            )
        }
            .onFailure {
                logger.error("Could not fingerprint ${file.name}; importing without one", it)
            }
            .getOrNull()

        val placed = try {
            place(staged, container, fingerprint)
        } catch (e: Exception) {
            logger.error("Could not move ${file.name} into its edition folder", e)
            container.close()
            return staged.discard(e.castOrFindImportException()?.result ?: ImportResult.IMPORT_ERROR)
        }

        // Older editions are never removed automatically (O1-Q5): the new one goes beside them.
        val result = importTree(placed, plan.tree, fingerprint)
        notifyCallback(result, callback, file)
        return result
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

    /**
     * Where [file] is imported from. A source already inside the internal source directory is
     * imported in place. Anything else is unpacked into a fresh staging folder, because the folder
     * it finally lives in is named after its edition, which isn't known until its text is read.
     */
    private suspend fun stage(file: File): Staged {
        if (file.isInside(directoryProvider.internalSourceRCDirectory) &&
            !file.isInside(directoryProvider.sourceStagingDirectory)
        ) {
            return Staged(root = null, rcFile = file)
        }
        val root = directoryProvider.sourceStagingDirectory.resolve(UUID.randomUUID().toString())
        root.mkdirs()
        return try {
            Staged(root, copyToInternalDirectory(file, root))
        } catch (e: Exception) {
            root.deleteRecursively()
            throw e
        }
    }

    /**
     * Moves a staged source into its edition folder (see IResourceContainerDirectories
     * .getSourceEditionDirectory) and reopens it there. A source imported in place stays put.
     */
    private suspend fun place(staged: Staged, container: ResourceContainer, fingerprint: EditionFingerprint?): Placed {
        val root = staged.root ?: return Placed(container, staged.rcFile, editionRoot = null)
        // Without a fingerprint the code only has to keep this folder apart from the others.
        val code = fingerprint?.shortCode ?: UUID.randomUUID().toString().take(6)
        val editionDir = directoryProvider.getSourceEditionDirectory(container, code)
        container.close()

        if (editionDir.exists()) {
            if (isStoredSourcePath(editionDir)) throw ImportException(ImportResult.ALREADY_EXISTS)
            // Left behind by an import that failed after moving it.
            editionDir.deleteRecursively()
        }
        editionDir.parentFile.mkdirs()
        // The container's own folder becomes the edition folder. Staging unpacks a zip under its
        // file name (import_123/en_ulb/manifest.yaml), and those folders mean nothing here.
        val containerRoot = staged.rcFile
        if (!containerRoot.renameTo(editionDir)) {
            containerRoot.copyRecursively(editionDir, overwrite = true)
        }
        root.deleteRecursively()
        val rc = ResourceContainer.load(editionDir, OtterResourceContainerConfig())
        return Placed(rc, editionDir, editionDir)
    }

    private suspend fun isStoredSourcePath(dir: File): Boolean =
        metadataRepository.getAllSourcesSuspend().any { it.path.isInside(dir) }

    private suspend fun importTree(
        placed: Placed,
        tree: OtterTree<CollectionOrContent>,
        fingerprint: EditionFingerprint?
    ): ImportResult {
        val container = placed.container
        return runCatching {
            resourceContainerRepository.importResourceContainerSuspend(
                container, tree, container.manifest.dublinCore.language.identifier, fingerprint
            )
        }
            .onFailure {
                logger.error("Error in importFromInternalDirectory importing rc, file: ${placed.rcFile}", it)
            }
            // Only remove files this import created; a source imported in place is left alone.
            .also { if (it.getOrNull() != ImportResult.SUCCESS) placed.editionRoot?.deleteRecursively() }
            .getOrThrow()
    }

    /** A source ready to read: [rcFile], unpacked under the staging folder [root], or in place. */
    private class Staged(val root: File?, val rcFile: File) {
        /** Deletes what staging unpacked, never a source imported in place. */
        fun discard(result: ImportResult): ImportResult {
            root?.deleteRecursively()
            return result
        }
    }

    /** A source in its final folder; [editionRoot] is null when it was imported in place. */
    private class Placed(val container: ResourceContainer, val rcFile: File, val editionRoot: File?)

    private suspend fun copyToInternalDirectory(file: File, destinationDirectory: File): File {
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

    private suspend fun extractSourceToDir(source: File, dir: File): File {
        val targetDir = dir.resolve(source.nameWithoutExtension)
        directoryProvider
            .newFileReader(source)
            .use { fileReader -> fileReader.copyDirectorySuspend("/", targetDir) }

        targetDir.walk().forEach {
            if (it.isDirectory && it.resolve("manifest.yaml").exists()) {
                return it
            }
        }

        return targetDir
    }

    private suspend fun getVersification(rcDir: File): Versification? {
        // Try to find versification.json in the container
        val versificationFile = File(rcDir, "ingredients/versification.json")
        if (versificationFile.exists()) {
            try {
                return OTTER_JSON.decodeFromString(
                    ParatextVersification.serializer(),
                    versificationFile.readText()
                )
            } catch (e: Exception) {
                logger.error("Failed to parse versification.json", e)
            }
        }
        // Fallback to default
        return versificationRepository.getVersificationSuspend(StandardVersifications.DEFAULT)
    }

    private fun generateUsfmContent(bookSlug: String, versification: Versification): String {
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

private fun File.isInside(dir: File): Boolean {
    val path = canonicalFile.toPath()
    return path.startsWith(dir.canonicalFile.toPath())
}
