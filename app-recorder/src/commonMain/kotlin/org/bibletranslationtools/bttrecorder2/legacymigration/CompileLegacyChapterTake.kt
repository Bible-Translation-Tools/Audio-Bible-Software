package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.audio.OratureCueType
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.ConcatenateAudio
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.AudioFormatUnexpected
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.AudioRepaired
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.AudioUnreadable
import org.bibletranslationtools.bttrecorder2.legacymigration.MigrationProblem.InnerMarkersSetAside
import java.io.File

/**
 * Builds one chapter take from a [ChapterTakePlan]: the legacy takes joined in verse order, with a
 * verse marker where each verse or chunk starts and the recordist's own inner verse markers carried
 * at their offsets (M6-2).
 *
 * The old takes are only read. A take whose recording never finished is read through a repaired
 * copy (M6-1); one that can't be read, or isn't 44.1 kHz 16-bit mono, is left out and reported.
 * The same plan always gives the same bytes, so importing a rebuilt take again adds nothing.
 */
class CompileLegacyChapterTake(
    private val concatenateAudio: ConcatenateAudio,
    private val tempFiles: ITempFileProvider
) {
    sealed interface Result {
        val problems: List<MigrationProblem>

        data class Compiled(
            val file: File,
            val totalFrames: Int,
            /** In order of location: one per verse or chunk, plus the carried inner markers. */
            val markers: List<VerseMarker>,
            override val problems: List<MigrationProblem>
        ) : Result

        /** None of the plan's takes could be used, so no chapter take was written. */
        data class NothingUsable(override val problems: List<MigrationProblem>) : Result
    }

    /** One take, opened and checked, ready to join. */
    private class Usable(val piece: ChapterPiece, val audio: File, val frames: Int)

    /** Writes the chapter take for [plan] of chapter [chapter] to [destination], replacing it. */
    suspend fun execute(chapter: Int, plan: ChapterTakePlan, destination: File): Result = withContext(Dispatchers.IO) {
        val problems = mutableListOf<MigrationProblem>()
        val repairedCopies = mutableListOf<File>()
        try {
            val usable = plan.pieces.mapNotNull { piece -> open(chapter, piece, problems, repairedCopies) }
            if (usable.isEmpty()) return@withContext Result.NothingUsable(problems)

            val joined = concatenateAudio.execute(usable.map { it.audio }, includeMarkers = false).await()
            val markers = markersFor(chapter, usable, problems)
            OratureAudioFile(joined).apply {
                markers.forEach { addMarker(OratureCueType.VERSE, it) }
                update()
            }
            destination.parentFile?.mkdirs()
            joined.copyTo(destination, overwrite = true)
            joined.delete()

            Result.Compiled(destination, usable.sumOf { it.frames }, markers, problems)
        } finally {
            repairedCopies.forEach { it.delete() }
        }
    }

    /** [piece]'s audio, repaired if it has to be, or null when it can't be used. */
    private fun open(
        chapter: Int,
        piece: ChapterPiece,
        problems: MutableList<MigrationProblem>,
        repairedCopies: MutableList<File>
    ): Usable? {
        val take = piece.take
        val audio = when (val state = LegacyWavHeader.inspect(take.file)) {
            LegacyWavHeader.State.Readable -> take.file
            is LegacyWavHeader.State.Unrepairable -> {
                problems += AudioUnreadable(chapter, take, state.reason)
                return null
            }
            is LegacyWavHeader.State.NeedsRepair -> {
                val copy = tempFiles.createTempFile("legacy-take-${take.id}", ".wav")
                repairedCopies += copy
                LegacyWavHeader.repairedCopy(take.file, state.audioBytes, copy)
                problems += AudioRepaired(chapter, take)
                copy
            }
        }
        val file = try {
            OratureAudioFile(audio)
        } catch (e: Exception) {
            problems += AudioUnreadable(chapter, take, e.message ?: e::class.simpleName.orEmpty())
            return null
        }
        if (file.sampleRate != SAMPLE_RATE || file.bitsPerSample != BITS_PER_SAMPLE || file.channels != CHANNELS) {
            problems += AudioFormatUnexpected(
                chapter, take, "${file.sampleRate} Hz, ${file.bitsPerSample}-bit, ${file.channels} channel(s)"
            )
            return null
        }
        if (file.totalFrames <= 0) {
            problems += AudioUnreadable(chapter, take, "it holds no audio")
            return null
        }
        return Usable(piece, audio, file.totalFrames)
    }

    /**
     * A marker at the start of each verse or chunk, labelled with its whole range, and each inner
     * marker the recordist placed at a later verse of it, at that offset. Inner markers that don't
     * fit are set aside and reported; the audio is kept either way.
     */
    private fun markersFor(chapter: Int, usable: List<Usable>, problems: MutableList<MigrationProblem>): List<VerseMarker> {
        var offset = 0
        val markers = usable.flatMap { item ->
            val verses = item.piece.verses
            val start = VerseMarker(verses.first, verses.last, offset)
            // A marker for the verse the take starts with is the start marker already.
            val inner = item.piece.take.metadata?.markers.orEmpty().filterNot { it.verse == verses.first }
            val (carried, setAside) = inner.partition { marker ->
                val verse = marker.verse
                verse != null && verse in verses && marker.frame in 1L until item.frames.toLong()
            }
            if (setAside.isNotEmpty()) problems += InnerMarkersSetAside(chapter, item.piece.take, setAside)
            val placed = carried.distinctBy { it.verse }.map { VerseMarker(it.verse!!, it.verse!!, offset + it.frame.toInt()) }
            offset += item.frames
            listOf(start) + placed
        }
        return markers.sortedBy { it.location }
    }

    private companion object {
        // What the old BTT-Recorder always recorded.
        const val SAMPLE_RATE = 44100
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
    }
}
