package org.bibletranslationtools.otter.common.domain.content

import java.io.File

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

    data class Reserved(val number: Int, val file: File)

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

    /**
     * A name for staging a recording before it is moved into its reserved take file. It doesn't
     * match the take file name pattern, so export and import never mistake a leftover staging file
     * for a take.
     */
    fun stagingName(): String = "recording-${System.nanoTime()}.part"
}
