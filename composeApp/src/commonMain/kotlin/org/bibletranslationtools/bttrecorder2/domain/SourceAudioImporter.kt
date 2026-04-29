package org.bibletranslationtools.bttrecorder2.domain

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import java.io.File

/**
 * Imports per-chapter source audio files into a project's source-audio directory.
 *
 * The accompanying chapter-marked audio is what
 * [org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceAudioAccessor.getUserMarkedChapter]
 * looks for at playback time, matched by the regex `_c(0*)<chapter>\\.`. So this
 * importer normalizes incoming filenames to that convention while preserving the
 * original audio data byte-for-byte (no transcoding).
 *
 * Chapter numbers are detected heuristically from the filename. Users typically
 * import chapter audio that already has `_c01`, `Chapter 3`, or similar in the
 * name; everything else is reported as skipped so we don't silently misfile audio.
 */
class SourceAudioImporter(
    private val directoryProvider: IDirectoryProvider
) {
    data class Result(
        val imported: List<File> = emptyList(),
        val skipped: List<String> = emptyList(),
        val errors: List<String> = emptyList()
    ) {
        val total: Int get() = imported.size + skipped.size + errors.size
    }

    /**
     * Copies each [files] entry into the project's source audio directory.
     * Returns counts of imported / skipped / failed files for caller-side feedback.
     */
    suspend fun importForWorkbook(
        descriptor: WorkbookDescriptor,
        files: List<PlatformFile>
    ): Result {
        val sourceMeta = descriptor.sourceCollection.resourceContainer
            ?: return Result(errors = files.map { "${it.name}: source metadata missing" })
        val targetMeta = descriptor.targetCollection.resourceContainer
        val bookSlug = descriptor.targetCollection.slug

        val targetDir = directoryProvider.getProjectSourceAudioDirectory(
            sourceMeta,
            targetMeta,
            bookSlug
        ).also { it.mkdirs() }

        val imported = mutableListOf<File>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (platformFile in files) {
            val originalName = platformFile.name
            try {
                val ext = originalName.substringAfterLast('.', "").lowercase()
                if (ext.isEmpty() || !AudioFileFormat.isSupported(ext)) {
                    skipped.add("$originalName (unsupported format)")
                    continue
                }

                val chapter = parseChapterFromName(originalName)
                if (chapter == null) {
                    skipped.add("$originalName (unable to detect chapter)")
                    continue
                }

                val destFile = File(targetDir, formatChapterFileName(bookSlug, chapter, ext))
                destFile.writeBytes(platformFile.readBytes())
                imported.add(destFile)
            } catch (e: Exception) {
                errors.add("$originalName: ${e.message ?: e::class.simpleName ?: "unknown error"}")
            }
        }

        return Result(imported = imported, skipped = skipped, errors = errors)
    }

    /**
     * Returns true if any chapter-marked audio file is present in the project's
     * source-audio directory (i.e., a user has imported source audio).
     */
    fun hasUserImportedSourceAudio(descriptor: WorkbookDescriptor): Boolean {
        val sourceMeta = descriptor.sourceCollection.resourceContainer ?: return false
        val targetMeta = descriptor.targetCollection.resourceContainer
        val bookSlug = descriptor.targetCollection.slug
        val dir = directoryProvider.getProjectSourceAudioDirectory(sourceMeta, targetMeta, bookSlug)
        if (!dir.exists()) return false
        return dir.listFiles()?.any { file ->
            chapterPattern.containsMatchIn(file.name) && AudioFileFormat.isSupported(file.extension)
        } == true
    }

    companion object {
        // Patterns ordered most-specific to least-specific. The first capture group
        // that yields a positive integer is treated as the chapter number.
        private val chapterPatterns = listOf(
            Regex("_c0*(\\d+)\\.", RegexOption.IGNORE_CASE),               // already in our format
            Regex("[_\\s-]ch(?:apter)?[_\\s-]?0*(\\d+)\\b", RegexOption.IGNORE_CASE),
            Regex("(?:^|[\\s_-])0*(\\d{1,3})(?=[\\s_.\\-])"),               // bare 1-3 digit number
        )
        private val chapterPattern = Regex("_c(0*)\\d+\\.", RegexOption.IGNORE_CASE)

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
