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
package org.bibletranslationtools.otter.common.domain.content

import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.rx2.rxSingle
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Gives every chapter whose chunks all have a selected take, but which has no chapter take of its
 * own, a chapter take compiled from those takes. The recorder records verses, and source export
 * only exports chapter takes. Each chunk's verse marker comes from its verse range, as recorded
 * takes carry none.
 *
 * The new take becomes the selected one, but asynchronously, once the repository has inserted it
 * (see [SaveAudioAsNewTake]). So [execute] returns the compiled takes by chapter, for a caller
 * that needs them now.
 */
class CompileCompletedChapters(
    private val concatenateAudio: ConcatenateAudio,
    private val saveAudioAsNewTake: SaveAudioAsNewTake
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Compiles only the chapters in [chapterFilter], or every chapter when it is null.
     * @return the compiled take of each chapter that was compiled, by chapter sort
     */
    fun execute(workbook: Workbook, chapterFilter: List<Int>?): Single<Map<Int, Take>> {
        return workbook.target.chapters
            .filter { chapterFilter?.contains(it.sort) ?: true }
            .filter { !it.hasSelectedAudio() }
            .concatMapMaybe { chapter ->
                val chunks = chapter.chunks.blockingGet()
                if (chunks.isEmpty() || !chunks.all { it.hasSelectedAudio() }) {
                    return@concatMapMaybe Maybe.empty()
                }
                val takes = chunks.map { it.audio.getSelectedTake()!!.file }
                logger.info("Compiling chunk/verse takes for completed chapter #${chapter.sort}")
                concatenateAudio.execute(takes, includeMarkers = false)
                    .doOnSuccess { compiled -> addVerseMarkers(compiled, chunks.map { it.start..it.end }, takes) }
                    .flatMap { compiled ->
                        rxSingle { saveAudioAsNewTake.execute(workbook, chapter, null, chapter, compiled) }
                            .doFinally { compiled.delete() }
                    }
                    .map { take -> chapter.sort to take }
                    .toMaybe()
            }
            .collectInto(mutableMapOf<Int, Take>()) { map, (sort, take) -> map[sort] = take }
            .map { it.toMap() }
            .doOnError { logger.error("Error while compiling completed chapters.", it) }
            .subscribeOn(Schedulers.io())
    }
}

/** Marks where each verse range starts in [compiled], which holds [takes] concatenated in order. */
internal fun addVerseMarkers(compiled: File, verses: List<IntRange>, takes: List<File>) {
    val audio = OratureAudioFile(compiled)
    var location = 0
    verses.zip(takes).forEach { (range, take) ->
        audio.addMarker(VerseMarker(range.first, range.last, location))
        location += OratureAudioFile(take).totalFrames
    }
    audio.update()
}
