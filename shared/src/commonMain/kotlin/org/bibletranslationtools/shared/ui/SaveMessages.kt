package org.bibletranslationtools.shared.ui

import org.bibletranslationtools.otter.common.data.workbook.SaveFailure
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.err_save_disk_full
import org.bibletranslationtools.shared.resources.err_save_failed
import org.bibletranslationtools.shared.resources.err_save_file_missing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** The localized message for a failed workbook save. */
val SaveFailure.message: StringResource
    get() = when (this) {
        SaveFailure.DISK_FULL -> Res.string.err_save_disk_full
        SaveFailure.FILE_MISSING -> Res.string.err_save_file_missing
        SaveFailure.COULD_NOT_SAVE -> Res.string.err_save_failed
    }

/** The localized message to show for this result, or null when there is nothing to tell the user. */
suspend fun SaveResult<*>.failureMessage(): String? =
    (this as? SaveResult.Failed)?.failure?.message?.let { getString(it) }
