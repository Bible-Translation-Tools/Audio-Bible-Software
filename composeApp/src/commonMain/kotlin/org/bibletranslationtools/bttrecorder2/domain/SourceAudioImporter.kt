package org.bibletranslationtools.bttrecorder2.domain

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.reactivex.Single
import kotlinx.coroutines.CancellationException
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.OratureFileFormat
import org.bibletranslationtools.otter.common.data.ScriptureBurritoFileFormat
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.importer.ImportCallbackParameter
import org.bibletranslationtools.otter.common.domain.project.importer.ImportOptions
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import java.io.File

/**
 * Imports per-chapter source audio files into a project's source-audio directory.
 *
 * Two flavors of source supported, mirroring how Orature accepts source audio:
 *   1. **Loose audio** (.wav / .mp3) — handled by [importLooseAudio]; the file is
 *      copied as-is and renamed to `<bookSlug>_c<NN>.<ext>` so [SourceAudioAccessor]
 *      can find it.
 *   2. **Container archives** (.orature / .zip / .burrito) — delegated to Orature's
 *      [ImportProjectUseCase.import]; the use case handles format detection (RC vs
 *      Burrito), Burrito-to-RC conversion if needed, and copying audio from the
 *      `.apps/orature/source/audio/` directory inside the archive into the project's
 *      `sourceAudioDir` via `ProjectFilesAccessor.copySourceFiles(IFileReader)`.
 *
 * The accompanying chapter-marked audio is what
 * [org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceAudioAccessor.getUserMarkedChapter]
 * looks for at playback time, matched by the regex `_c(0*)<chapter>\\.`. So both
 * import paths normalize incoming filenames to that convention while preserving
 * the audio data byte-for-byte (no transcoding).
 */
class SourceAudioImporter(
    private val directoryProvider: IDirectoryProvider,
    private val importProjectUseCase: ImportProjectUseCase
) {
    data class Result(
        val imported: List<File> = emptyList(),
        val skipped: List<String> = emptyList(),
        val errors: List<String> = emptyList()
    ) {
        val total: Int get() = imported.size + skipped.size + errors.size

        operator fun plus(other: Result): Result = Result(
            imported = imported + other.imported,
            skipped = skipped + other.skipped,
            errors = errors + other.errors
        )
    }

    /**
     * Single entry point: dispatches each [files] entry to either the loose-audio
     * path or the container path based on its extension. Mixed selections work.
     */
    suspend fun importForWorkbook(
        descriptor: WorkbookDescriptor,
        files: List<PlatformFile>
    ): Result {
        var aggregate = Result()
        for (platformFile in files) {
            val ext = platformFile.name.substringAfterLast('.', "").lowercase()
            aggregate += when {
                AudioFileFormat.isSupported(ext) -> importLooseAudio(descriptor, platformFile)
                isContainerExtension(ext) -> importContainer(descriptor, platformFile)
                else -> Result(skipped = listOf("${platformFile.name} (unsupported format)"))
            }
        }
        return aggregate
    }

    /**
     * Returns true if any chapter-marked audio file is present in the project's
     * source-audio directory (i.e., a user has imported source audio).
     */
    fun hasUserImportedSourceAudio(descriptor: WorkbookDescriptor): Boolean {
        val targetDir = sourceAudioDirFor(descriptor) ?: return false
        if (!targetDir.exists()) return false
        return targetDir.listFiles()?.any { file ->
            chapterPattern.containsMatchIn(file.name) && AudioFileFormat.isSupported(file.extension)
        } == true
    }

    // -------------------------------------------------------------------------
    // Loose audio import (single .wav / .mp3 file)
    // -------------------------------------------------------------------------

    private suspend fun importLooseAudio(
        descriptor: WorkbookDescriptor,
        platformFile: PlatformFile
    ): Result {
        val targetDir = sourceAudioDirFor(descriptor)
            ?: return Result(errors = listOf("${platformFile.name}: source metadata missing"))
        targetDir.mkdirs()

        val originalName = platformFile.name
        return try {
            val ext = originalName.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty() || !AudioFileFormat.isSupported(ext)) {
                Result(skipped = listOf("$originalName (unsupported format)"))
            } else {
                val chapter = parseChapterFromName(originalName)
                if (chapter == null) {
                    Result(skipped = listOf("$originalName (unable to detect chapter)"))
                } else {
                    val destFile = File(targetDir, formatChapterFileName(descriptor.targetCollection.slug, chapter, ext))
                    destFile.writeBytes(platformFile.readBytes())
                    Result(imported = listOf(destFile))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(errors = listOf("$originalName: ${e.message ?: e::class.simpleName ?: "unknown error"}"))
        }
    }

    // -------------------------------------------------------------------------
    // Container import (.orature / .zip / .burrito) via Orature's ImportProjectUseCase
    // -------------------------------------------------------------------------

    private suspend fun importContainer(
        descriptor: WorkbookDescriptor,
        platformFile: PlatformFile
    ): Result {
        // FileKit gives us bytes, not a File path. Stage the archive to a temp
        // file so ImportProjectUseCase (which needs a real File) can open it.
        val stagedArchive = stageArchive(platformFile) ?: return Result(
            errors = listOf("${platformFile.name}: failed to stage archive")
        )

        // Snapshot audio files already present so we can compute the delta after import.
        val targetDir = sourceAudioDirFor(descriptor)
        val filesBeforeImport: Set<String> = targetDir
            ?.takeIf { it.exists() }
            ?.listFiles()
            ?.filter { AudioFileFormat.isSupported(it.extension.lowercase()) }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        return try {
            // An auto-confirm callback so the import pipeline doesn't block waiting for
            // user input (e.g., version-conflict confirmation). We always confirm here
            // because the user explicitly chose to import this file.
            val autoConfirm = object : ProjectImporterCallback {
                override fun onRequestUserInput(): Single<ImportOptions> =
                    Single.just(ImportOptions(confirmed = true))

                override fun onRequestUserInput(parameter: ImportCallbackParameter): Single<ImportOptions> =
                    Single.just(ImportOptions(confirmed = true))

                override fun onNotifyProgress(localizeKey: String?, message: String?, percent: Double?) = Unit
                override fun onNotifySuccess(language: String?, project: String?, workbookDescriptor: org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor?) = Unit
                override fun onError(filePath: String) = Unit
            }

            // blockingGet() is safe here because this suspend function is always called
            // from a withContext(Dispatchers.IO) scope in the UI layer.
            val importResult: ImportResult = importProjectUseCase
                .import(stagedArchive, autoConfirm)
                .blockingGet()

            when (importResult) {
                ImportResult.SUCCESS, ImportResult.ALREADY_EXISTS -> {
                    // Count audio files that are genuinely new in sourceAudioDir.
                    val newFiles: List<File> = targetDir
                        ?.takeIf { it.exists() }
                        ?.listFiles()
                        ?.filter {
                            AudioFileFormat.isSupported(it.extension.lowercase()) &&
                                it.name !in filesBeforeImport
                        }
                        ?: emptyList()

                    if (newFiles.isNotEmpty()) {
                        Result(imported = newFiles)
                    } else {
                        // Either already up-to-date or audio lives under a different
                        // workbook's sourceAudioDir (different language/source match).
                        // We still count the container as one successful import so the
                        // UI reflects that something happened.
                        Result(skipped = listOf("${platformFile.name} (already up to date)"))
                    }
                }

                ImportResult.INVALID_RC, ImportResult.INVALID_CONTENT,
                ImportResult.LOAD_RC_ERROR, ImportResult.UNSUPPORTED_CONTENT ->
                    Result(errors = listOf("${platformFile.name}: not a valid source audio container"))

                ImportResult.UNMATCHED_HELP ->
                    Result(errors = listOf("${platformFile.name}: source has no matching project — import the base source language first"))

                ImportResult.DEPENDENCY_CONSTRAINT ->
                    Result(errors = listOf("${platformFile.name}: missing dependency — import the source language RC first"))

                ImportResult.IMPORT_ERROR, ImportResult.FAILED ->
                    Result(errors = listOf("${platformFile.name}: import failed"))

                ImportResult.ABORTED ->
                    Result(skipped = listOf("${platformFile.name} (import cancelled)"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(errors = listOf("${platformFile.name}: ${e.message ?: e::class.simpleName ?: "unknown error"}"))
        } finally {
            runCatching { stagedArchive.delete() }
        }
    }

    private suspend fun stageArchive(platformFile: PlatformFile): File? {
        return try {
            val ext = platformFile.name.substringAfterLast('.', "").lowercase()
            val staged = File.createTempFile(
                "src_audio_archive_",
                ".$ext",
                directoryProvider.tempDirectory
            )
            staged.writeBytes(platformFile.readBytes())
            staged
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sourceAudioDirFor(descriptor: WorkbookDescriptor): File? {
        val sourceMeta = descriptor.sourceCollection.resourceContainer ?: return null
        val targetMeta = descriptor.targetCollection.resourceContainer
        val bookSlug = descriptor.targetCollection.slug
        return directoryProvider.getProjectSourceAudioDirectory(sourceMeta, targetMeta, bookSlug)
    }

    private fun isContainerExtension(ext: String): Boolean {
        return ext in OratureFileFormat.extensionList ||
            ext in ScriptureBurritoFileFormat.extensionList
    }

    companion object {
        // Patterns ordered most-specific to least-specific. The first capture group
        // that yields a positive integer is treated as the chapter number.
        private val chapterPatterns = listOf(
            Regex("_c0*(\\d+)\\.", RegexOption.IGNORE_CASE),                         // already in our format
            Regex("[_\\s-]ch(?:apter)?[_\\s-]?0*(\\d+)\\b", RegexOption.IGNORE_CASE), // chapter1, _ch_2
            Regex("(?:^|[\\s_-])0*(\\d{1,3})(?=[\\s_.\\-])"),                        // bare 1-3 digit number
        )
        private val chapterPattern = Regex("_c(0*)\\d+\\.", RegexOption.IGNORE_CASE)

        /**
         * Union of audio + container extensions that should be acceptable by the
         * file picker. Exposed for the UI so callers don't duplicate the list.
         */
        val pickerExtensions: List<String> = (
            AudioFileFormat.values().map { it.extension } +
                OratureFileFormat.extensionList +
                ScriptureBurritoFileFormat.extensionList
            ).map { it.lowercase() }.distinct()

        internal fun parseChapterFromName(name: String): Int? {
            for (pattern in chapterPatterns) {
                val n = pattern.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (n != null && n > 0) return n
            }
            return null
        }

        internal fun formatChapterFileName(bookSlug: String, chapter: Int, extension: String): String {
            // Mirror the legacy chapter format used by Orature's FileNamer: zero-padded
            // 2-digit by default. The 3-digit format is reserved for projects with
            // 100+ chapters; the source audio import flow doesn't have a chapterCount
            // available, so we use 2-digit padding which the playback regex tolerates
            // (`_c(0*)<chapter>\.` matches any number of leading zeros).
            val padded = chapter.toString().padStart(2, '0')
            return "${bookSlug}_c$padded.$extension"
        }
    }
}
