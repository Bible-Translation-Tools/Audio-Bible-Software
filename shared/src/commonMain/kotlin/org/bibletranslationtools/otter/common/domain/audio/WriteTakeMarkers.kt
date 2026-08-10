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
package org.bibletranslationtools.otter.common.domain.audio

import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.audio.OratureCueType
import java.io.File

/**
 * Replaces the markers stored in a take's WAV cue chunks.
 *
 * Markers are metadata, not audio: this rewrites the cue chunks of the file in place and leaves
 * the PCM untouched — no re-encode and no new take. Both apps had their own copy of the
 * clear-then-add-then-update sequence ([NarrationTakeModifier.modifyMetadata]'s inner task in
 * Orature, `PlaybackViewModel.saveVerseMarkers` in the recorder).
 *
 * [replacing] exists because the two callers genuinely disagree about it, and the disagreement
 * predates this class:
 *
 *  - narration passes [NARRATION_CUE_TYPES], keeping any `CHUNK` and `LICENSE` cues on the file;
 *  - the recorder passes [ALL_CUE_TYPES], dropping them.
 *
 * Making it a parameter keeps both callers behaving exactly as they did rather than silently
 * picking a winner. Whether the recorder *should* be discarding a take's license cue when the
 * user saves verse markers is a real question, but it is a behaviour change and not this class's
 * to make.
 *
 * Callers are responsible for their own threading and for pausing playback — writing while
 * another handle reads the same file is the caller's problem to avoid.
 */
class WriteTakeMarkers {

    companion object {
        /**
         * The three marker types narration owns. Preserves `CHUNK` and `LICENSE` cues, which
         * narration never writes and must not destroy.
         */
        val NARRATION_CUE_TYPES: Set<OratureCueType> = setOf(
            OratureCueType.VERSE,
            OratureCueType.CHAPTER_TITLE,
            OratureCueType.BOOK_TITLE
        )

        /** Every cue type, i.e. the file's marker metadata is fully replaced. */
        val ALL_CUE_TYPES: Set<OratureCueType> = OratureCueType.entries.toSet()
    }

    /**
     * Clears the [replacing] cue types from [file], writes [markers], and flushes to disk.
     *
     * @return the markers as re-read from the file, so callers can refresh from the source of
     *   truth rather than assuming the write round-tripped.
     */
    fun execute(
        file: File,
        markers: List<AudioMarker>,
        replacing: Set<OratureCueType>
    ): List<AudioMarker> {
        val audioFile = OratureAudioFile(file)
        replacing.forEach { audioFile.clearCuesFromMap(it) }
        markers.forEach { marker ->
            audioFile.addMarker(audioFile.getMarkerTypeFromClass(marker::class), marker)
        }
        audioFile.update()
        return OratureAudioFile(file).getMarkers().sortedBy { it.location }
    }
}
