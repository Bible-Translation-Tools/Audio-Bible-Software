package org.bibletranslationtools.otter.common.data.workbook

import org.bibletranslationtools.otter.common.data.primitives.MimeType
import java.io.File
import java.time.LocalDate

/**
 * A take that has not been saved yet. It has no [TakeId]: the store assigns one when it saves the
 * take, and nothing sees the take until then.
 *
 * [file] is the take's own file, already holding its audio: claim it with
 * [org.bibletranslationtools.otter.common.domain.content.TakeFiles.reserve] so it can't replace
 * another take's recording.
 */
data class NewTake(
    val file: File,
    val number: Int,
    val format: MimeType = MimeType.WAV,
    val createdOn: LocalDate = LocalDate.now(),
    val checking: TakeCheckingState = TakeCheckingState(org.bibletranslationtools.otter.common.data.primitives.CheckingStatus.UNCHECKED, null)
) {
    val name: String get() = file.name
}
