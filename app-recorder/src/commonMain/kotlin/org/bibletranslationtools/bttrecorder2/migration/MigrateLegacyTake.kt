package org.bibletranslationtools.bttrecorder2.migration

import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.content.FileNamer
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Copies one legacy take into a migrated project and registers it against its content row.
 *
 * Legacy WAVs are already 44.1 kHz mono 16-bit behind a canonical 44-byte header, which is what the
 * recorder itself writes, so the PCM is copied byte for byte and only the metadata is rewritten.
 */
class MigrateLegacyTake(
    private val takeRepository: ITakeRepository,
    private val writeTakeMarkers: WriteTakeMarkers
) {

    private val logger = LoggerFactory.getLogger(MigrateLegacyTake::class.java)

    sealed interface Result {
        data class Copied(val take: Take, val frames: Int) : Result

        /**
         * A take with this audio is already registered, so nothing was copied. The existing row is
         * returned because an interrupted run may not have persisted the take selection, leaving
         * the caller to apply it.
         */
        data class AlreadyPresent(val take: Take) : Result

        data class Skipped(val reason: String) : Result
    }

    /**
     * @param preferredNumber the take's position in legacy order, 1-based. Being deterministic, a
     *   resumed run lands on the same filenames and recognises its own earlier work. It is bumped
     *   only when the slot holds different audio, which is what renumbers takes when several legacy
     *   projects merge into one migrated project.
     * @param select whether this take was the legacy `units.chosen_take_fk`
     */
    fun execute(
        source: File,
        destinationDir: File,
        namer: FileNamer,
        content: Content,
        preferredNumber: Int,
        select: Boolean
    ): Result {
        val sourceFrames = framesOf(source)
            ?: return Result.Skipped("${source.name}: not a readable WAV")
        if (sourceFrames <= 0) {
            return Result.Skipped("${source.name}: no audio")
        }

        val existing = takeRepository.getByContent(content, includeDeleted = true).blockingGet()
        var number = preferredNumber
        var destination = File(destinationDir, namer.generateName(number, AudioFileFormat.WAV))

        // Find a free slot. A slot holding this same audio is this take, already migrated; a slot
        // holding anything else moves to the next number instead of being overwritten, whether that
        // is a take recorded in between or one migrated from another legacy project.
        //
        // Sameness is decided on the PCM rather than the frame count, since only metadata is
        // rewritten on copy. Two different recordings of equal length would otherwise compare equal,
        // dropping a take and, if it was the chosen one, selecting another project's audio.
        val sourcePcm = lazy { pcmDigest(source) }
        while (true) {
            val occupant = existing.firstOrNull { it.filename == destination.name }
            val inSlot = occupant?.path ?: destination.takeIf { it.exists() }
            if (inSlot != null) {
                if (isSameAudio(inSlot, sourceFrames, sourcePcm)) {
                    // A file with no row of its own: an interrupted run copied it but never
                    // inserted it, so fall through and register it.
                    if (occupant != null) return Result.AlreadyPresent(occupant)
                    break
                }
                number++
                destination = File(destinationDir, namer.generateName(number, AudioFileFormat.WAV))
                continue
            }
            break
        }

        return try {
            destinationDir.mkdirs()
            source.copyTo(destination, overwrite = true)

            // One cue at frame 0 spanning the whole unit, which is what the recorder itself
            // writes for a take at chunk level. A cue is required rather than optional because
            // `SourceProjectExporter` skips any take whose cue list is empty.
            //
            // ALL_CUE_TYPES clears every Orature cue type first, and the rewrite truncates at the
            // end of the audio section and re-emits only the chunks `WavMetadata` understands, so
            // the legacy LIST/INFO/IART block and its bare-numbered cues fall away with it.
            writeTakeMarkers.execute(
                destination,
                listOf(VerseMarker(content.start, content.end, 0)),
                WriteTakeMarkers.ALL_CUE_TYPES
            )

            val copiedFrames = framesOf(destination)
            if (copiedFrames != sourceFrames) {
                destination.delete()
                return Result.Skipped(
                    "${source.name}: copied $copiedFrames frames, expected $sourceFrames"
                )
            }

            val take = Take(
                filename = destination.name,
                path = destination,
                number = number,
                created = LocalDate.now(),
                deleted = null,
                played = false,
                // The legacy `chapters.checking` value is a per-chapter Door43 level rather than
                // per-take review progress, so there is nothing to map onto. Any other status would
                // also need a full-file checksum, since `Chunk.checkingStatus` reverts a status
                // whose checksum does not match.
                checkingStatus = CheckingStatus.UNCHECKED,
                checksum = null,
                markers = emptyList()
            )
            take.id = takeRepository.insertForContent(take, content).blockingGet()

            if (select) {
                content.selectedTake = take
            }
            Result.Copied(take, copiedFrames)
        } catch (e: Exception) {
            logger.error("Failed to migrate take ${source.path} -> ${destination.path}", e)
            runCatching { destination.delete() }
            Result.Skipped("${source.name}: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Frame count from the WAV header, or null when the file will not parse. Legacy files can carry
     * a malformed audio-length field, on which `WavFile`'s constructor throws, so an unreadable take
     * is skipped and reported rather than fatal.
     */
    private fun framesOf(file: File): Int? = runCatching {
        if (!file.isFile) return null
        OratureAudioFile(file).totalFrames
    }.getOrNull()

    /**
     * Whether [candidate] holds the same audio as the take being migrated. The frame count is
     * checked first as a header read, and the digest only when that already matches.
     */
    private fun isSameAudio(candidate: File, sourceFrames: Int, sourcePcm: Lazy<String?>): Boolean {
        if (framesOf(candidate) != sourceFrames) return false
        val expected = sourcePcm.value ?: return false
        return pcmDigest(candidate) == expected
    }

    /**
     * SHA-256 of the decoded PCM, or null when the file will not read. It covers no header or
     * metadata, so a migrated copy hashes equal to the legacy original it came from despite the
     * rewritten cues.
     */
    private fun pcmDigest(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        OratureAudioFile(file).reader().use { reader ->
            reader.open()
            val buffer = ByteArray(BUFFER_BYTES)
            while (reader.hasRemaining()) {
                val read = reader.getPcmBuffer(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private companion object {
        const val BUFFER_BYTES = 1 shl 16
    }
}
