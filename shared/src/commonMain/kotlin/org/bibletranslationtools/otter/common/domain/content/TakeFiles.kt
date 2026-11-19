package org.bibletranslationtools.otter.common.domain.content

import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.Take
import java.io.File
import java.time.LocalDate

/**
 * Claims a take file that nothing else uses yet.
 *
 * A take's file name comes from its chapter, its chunk's position and its take number, and the take
 * number only counts the takes one content row knows about. So a file with the generated name can
 * already exist: an earlier chunk layout's take, an orphan, or a take another writer is saving at
 * the same moment. Every writer used to copy or record over it with `overwrite = true`, replacing
 * someone else's audio.
 *
 * [reserve] instead tries the take number it is given and counts up until it finds a name that is
 * free, and creates that file empty in the same step (`createNewFile` is atomic), so no other writer
 * can claim it. The caller then owns the file and writes the audio into it.
 */
object TakeFiles {

    data class Reserved(val number: Int, val file: File) {
        /** The take for this file, not saved yet. */
        fun toTake(): Take =
            Take(name = file.name, file = file, number = number, format = MimeType.WAV, createdTimestamp = LocalDate.now())
    }

    /**
     * @param dir the chapter's take folder; created if missing
     * @param firstNumber the take number to try first, usually the element's next take number
     * @param nameFor the file name for a take number
     */
    fun reserve(dir: File, firstNumber: Int, nameFor: (Int) -> String): Reserved {
        dir.mkdirs()
        var number = firstNumber
        while (true) {
            val file = dir.resolve(nameFor(number))
            if (file.createNewFile()) return Reserved(number, file)
            number++
        }
    }
}
