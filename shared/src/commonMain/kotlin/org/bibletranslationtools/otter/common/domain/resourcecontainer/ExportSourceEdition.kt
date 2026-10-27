package org.bibletranslationtools.otter.common.domain.resourcecontainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionFingerprintRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes an installed source edition, text and source audio, to a zip that can be shared and
 * imported elsewhere. The files go in unchanged, so importing the zip gives the same edition (same
 * fingerprints): it is recognised as one already installed rather than fanning out into a new one.
 */
class ExportSourceEdition(private val fingerprintRepository: IEditionFingerprintRepository) {

    /**
     * Writes [edition] into [directory] as `<language>_<identifier>_v<version>[_<code>].zip`, not
     * overwriting a file already there, and returns the zip.
     */
    suspend fun export(edition: ResourceMetadata, directory: File): File {
        val code = fingerprintRepository.get(edition.id)?.shortCode
        return withContext(Dispatchers.IO) {
            val source = edition.path
            require(source.exists()) { "Source files not found at $source" }
            val root = "${edition.language.slug}_${edition.identifier}"
            val target = uniqueFile(directory, fileNameOf(edition, code))
            if (source.isFile) {
                // Stored as a zip already: share it as it is.
                source.copyTo(target)
            } else {
                ZipOutputStream(target.outputStream().buffered()).use { zip ->
                    source.walkTopDown().filter { it.isFile }.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
                        zip.putNextEntry(ZipEntry("$root/${file.relativeTo(source).invariantSeparatorsPath}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            target
        }
    }

    private fun fileNameOf(edition: ResourceMetadata, code: String?): String {
        val version = edition.version.trim().trim('"', '\'').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "none" }
        val suffix = code?.let { "_$it" }.orEmpty()
        return "${edition.language.slug}_${edition.identifier}_v$version$suffix.zip"
    }

    private fun uniqueFile(directory: File, name: String): File {
        directory.mkdirs()
        val base = name.removeSuffix(".zip")
        return generateSequence(1) { it + 1 }
            .map { n -> directory.resolve(if (n == 1) name else "$base ($n).zip") }
            .first { !it.exists() }
    }
}
