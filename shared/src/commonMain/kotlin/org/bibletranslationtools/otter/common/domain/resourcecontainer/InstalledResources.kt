package org.bibletranslationtools.otter.common.domain.resourcecontainer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.audio.AudioMetadataFileFormat
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.ZipFile

/**
 * An installed source (text, with any source audio it carries) as the Resources drawer shows it.
 *
 * @property distinguishingCode set when another installed edition would otherwise look the same.
 * @property sizeBytes what it takes on disk, audio included.
 * @property audioBytes how much of that is source audio (audio files and their marker files).
 * @property usedByLanguages the target languages of the projects made from it.
 * @property heldBackChapters project chapters keeping its verse structure (held back on an upgrade).
 * @property linkedTo other installed resources linked to it, such as notes linked to a Bible.
 */
data class InstalledResource(
    val edition: ResourceMetadata,
    val distinguishingCode: String?,
    val sizeBytes: Long,
    val audioBytes: Long,
    val usedByLanguages: List<Language>,
    val heldBackChapters: Int,
    val linkedTo: List<ResourceMetadata>
) {
    /** Nothing uses it, so it can be removed. */
    val removable: Boolean get() = usedByLanguages.isEmpty() && heldBackChapters == 0 && linkedTo.isEmpty()
}

/**
 * The sources installed on this device, for managing them: what each takes on disk, what uses it,
 * and removing one nothing uses. Nothing is ever removed automatically (O1-Q5); this is how a user
 * does it.
 */
class InstalledResources(
    private val metadataRepository: IResourceMetadataRepository,
    private val upgradeRepository: IEditionUpgradeRepository,
    private val describeSourceEditions: DescribeSourceEditions,
    private val editionLifecycle: EditionLifecycle
) {
    private val logger = LoggerFactory.getLogger(InstalledResources::class.java)

    /** Every installed source, by language, then identifier, newest edition first. */
    suspend fun list(): List<InstalledResource> {
        val sources = metadataRepository.getAllSourcesSuspend()
        val summaries = describeSourceEditions.describeAll(sources)
        return sources
            .sortedWith(
                compareBy<ResourceMetadata> { it.language.anglicizedName.lowercase() }
                    .thenBy { it.identifier }
                    .then(EditionOrder.newestFirst)
            )
            .map { edition ->
                val (size, audio) = withContext(Dispatchers.IO) { sizeOf(edition.path) }
                InstalledResource(
                    edition = edition,
                    distinguishingCode = summaries[edition.id]?.distinguishingCode,
                    sizeBytes = size,
                    audioBytes = audio,
                    usedByLanguages = metadataRepository.getAllDerivativesSuspend(edition)
                        .map { it.language }
                        .distinctBy { it.slug }
                        .sortedBy { it.anglicizedName.lowercase() },
                    heldBackChapters = withContext(Dispatchers.IO) { upgradeRepository.chapterUsage(edition.id) },
                    linkedTo = metadataRepository.getLinkedSuspend(edition)
                )
            }
    }

    /** Removes [edition] and its files, if nothing uses it; true when it was removed. */
    suspend fun remove(edition: ResourceMetadata): Boolean = editionLifecycle.removeIfUnused(edition)

    /** (all bytes, audio bytes) of a source stored as a folder or a zip. */
    private fun sizeOf(path: File): Pair<Long, Long> = runCatching {
        when {
            path.isDirectory -> {
                var all = 0L
                var audio = 0L
                path.walkTopDown().filter { it.isFile }.forEach { file ->
                    val length = file.length()
                    all += length
                    if (isAudio(file.extension)) audio += length
                }
                all to audio
            }
            path.isFile -> ZipFile(path).use { zip ->
                val audio = zip.entries().asSequence()
                    .filter { !it.isDirectory && isAudio(it.name.substringAfterLast('.', "")) }
                    .sumOf { it.size.coerceAtLeast(0) }
                path.length() to audio
            }
            else -> 0L to 0L
        }
    }.getOrElse {
        logger.error("Could not measure $path", it)
        0L to 0L
    }

    private fun isAudio(extension: String) =
        AudioFileFormat.isSupported(extension) || AudioMetadataFileFormat.isSupported(extension)
}
