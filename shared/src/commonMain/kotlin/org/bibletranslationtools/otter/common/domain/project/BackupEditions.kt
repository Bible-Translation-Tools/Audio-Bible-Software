package org.bibletranslationtools.otter.common.domain.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.bibletranslationtools.otter.common.OTTER_JSON
import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
import org.bibletranslationtools.otter.common.api.io.zip.IFileWriter
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionFingerprintRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionUpgradeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionFingerprint
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionOrder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledSourceEditions
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.slf4j.LoggerFactory

/**
 * A source edition as a backup names it: enough to find the same edition on another device (its
 * identity and fingerprints) and to show which one it was (its label and dates).
 *
 * @property file where the edition's container is inside the backup, if it is embedded.
 */
@Serializable
data class BackupEdition(
    val language: String,
    val identifier: String,
    val creator: String,
    val version: String,
    val issued: String,
    val modified: String,
    val structureFingerprint: String? = null,
    val textFingerprint: String? = null,
    val file: String? = null
)

/** A chapter that keeps an earlier edition's verse structure (held back on an upgrade). */
@Serializable
data class BackupHeldChapter(val chapter: Int, val edition: BackupEdition)

/**
 * [RcConstants.SOURCE_EDITIONS_FILE]: which edition a backed-up book uses, and which of its
 * chapters keep an earlier one's verse structure.
 */
@Serializable
data class BackupEditionRecord(
    val formatVersion: Int = FORMAT_VERSION,
    val book: String,
    val edition: BackupEdition,
    val heldBack: List<BackupHeldChapter> = emptyList()
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/**
 * Writes and reads the source editions a backup carries, and finds them again on restore.
 *
 * The book's own edition is embedded where Orature puts it ([RcConstants.SOURCE_DIR]), so the
 * backup restores there as before. The editions of held-back chapters, and the record itself, go in
 * [RcConstants.OWN_APP_DIR], which Orature ignores (S11-Q2).
 */
class BackupEditions(
    private val upgradeRepository: IEditionUpgradeRepository,
    private val metadataRepository: IResourceMetadataRepository,
    private val fingerprintRepository: IEditionFingerprintRepository,
    private val installedEditions: InstalledSourceEditions
) {
    private val logger = LoggerFactory.getLogger(BackupEditions::class.java)

    /**
     * The record for project book [projectBookId], and the editions of its held-back chapters, to
     * embed beside it. [embeddedSourceFile] is where the book's own edition goes in the backup.
     */
    suspend fun recordFor(projectBookId: Int, embeddedSourceFile: String?): Pair<BackupEditionRecord, List<ResourceMetadata>>? {
        val book = withContext(Dispatchers.IO) { upgradeRepository.projectBook(projectBookId) } ?: return null
        val sources = metadataRepository.getAllSourcesSuspend().associateBy { it.id }
        val heldEditions = book.chapters
            .filter { it.structureEditionId != book.sourceEdition.id }
            .mapNotNull { chapter -> sources[chapter.structureEditionId]?.let { chapter.sort to it } }
        val extra = heldEditions.map { it.second }.distinctBy { it.id }
        val extraFiles = extra.associate { it.id to embeddedFileFor(it) }
        val record = BackupEditionRecord(
            book = book.slug,
            edition = describe(book.sourceEdition, embeddedSourceFile),
            heldBack = heldEditions.map { (sort, edition) -> BackupHeldChapter(sort, describe(edition, extraFiles[edition.id])) }
        )
        return record to extra
    }

    /** Where an edition of a held-back chapter goes in a backup. */
    fun embeddedFileFor(edition: ResourceMetadata): String =
        "${RcConstants.OWN_SOURCE_DIR}/${edition.language.slug}_${edition.identifier}_${edition.id}.zip"

    fun write(fileWriter: IFileWriter, record: BackupEditionRecord) {
        fileWriter.bufferedWriter(RcConstants.SOURCE_EDITIONS_FILE).use {
            it.write(OTTER_JSON.encodeToString(BackupEditionRecord.serializer(), record))
        }
    }

    /** The record in a backup, or null when it has none (made by Orature, or before this record existed). */
    fun read(fileReader: IFileReader): BackupEditionRecord? {
        if (!fileReader.exists(RcConstants.SOURCE_EDITIONS_FILE)) return null
        return runCatching {
            fileReader.stream(RcConstants.SOURCE_EDITIONS_FILE).bufferedReader().use {
                OTTER_JSON.decodeFromString(BackupEditionRecord.serializer(), it.readText())
            }
        }.onFailure { logger.error("Could not read ${RcConstants.SOURCE_EDITIONS_FILE}", it) }
            .getOrNull()
            ?.takeIf { it.formatVersion <= BackupEditionRecord.FORMAT_VERSION }
    }

    /** The installed edition that is the same edition as [edition]: same source and fingerprints. */
    suspend fun findInstalled(edition: BackupEdition): ResourceMetadata? {
        val structure = edition.structureFingerprint ?: return null
        val text = edition.textFingerprint ?: return null
        return installedEditions.editionsOf(edition.language, edition.identifier)
            .filter { it.creator == edition.creator }
            .firstOrNull { installed ->
                fingerprintRepository.get(installed.id)
                    ?.let { it.structureFingerprint == structure && it.textFingerprint == text } == true
            }
    }

    /** The installed edition of [languageSlug]/[identifier] whose content is [fingerprint]. */
    suspend fun findInstalled(languageSlug: String, identifier: String, fingerprint: EditionFingerprint): ResourceMetadata? =
        installedEditions.editionsOf(languageSlug, identifier).firstOrNull { installed ->
            fingerprintRepository.get(installed.id)?.let {
                it.structureFingerprint == fingerprint.structureFingerprint && it.textFingerprint == fingerprint.textFingerprint
            } == true
        }

    /**
     * For a backup that doesn't say which edition it used (S11-Q4): the installed edition with its
     * version label, or failing that the newest, or null when none of that source is installed.
     */
    suspend fun closestInstalled(languageSlug: String, identifier: String, version: String): ResourceMetadata? {
        val editions = installedEditions.editionsOf(languageSlug, identifier)
        return editions.firstOrNull { it.version.unquoted() == version.unquoted() }
            ?: editions.sortedWith(EditionOrder.newestFirst).firstOrNull()
    }

    private suspend fun describe(edition: ResourceMetadata, file: String?): BackupEdition {
        val fingerprint = fingerprintRepository.get(edition.id)
        return BackupEdition(
            language = edition.language.slug,
            identifier = edition.identifier,
            creator = edition.creator,
            version = edition.version.unquoted(),
            issued = edition.issued.toString(),
            modified = edition.modified.toString(),
            structureFingerprint = fingerprint?.structureFingerprint,
            textFingerprint = fingerprint?.textFingerprint,
            file = file
        )
    }

    /** Some manifests quote the version, e.g. `'12'` with the quotes. */
    private fun String.unquoted() = trim().trim('"', '\'')
}
