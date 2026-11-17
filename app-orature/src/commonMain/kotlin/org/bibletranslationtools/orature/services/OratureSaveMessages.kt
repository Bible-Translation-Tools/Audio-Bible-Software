package org.bibletranslationtools.orature.services

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.shared.ui.failureMessage

/**
 * Tells the user when a workbook change they made was not saved. The screens' own error states
 * replace the whole screen, which is right for a project that failed to open but wrong for one
 * change that failed: the user's work must stay in front of them. So a failed save is shown the
 * way import results are, as a snackbar at the app root ([org.bibletranslationtools.orature.ui.OratureApp]).
 *
 * Registered as a Koin single.
 */
class OratureSaveMessages {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Localized messages to show, one per failed save. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * Shows [result]'s failure, if it failed, and returns it unchanged. A saved result shows
     * nothing, and neither does a rejected one: a change refused because the workbook closed or
     * the chunk was replaced is not something the user can act on.
     */
    suspend fun <T> report(result: SaveResult<T>): SaveResult<T> {
        result.failureMessage()?.let { _messages.tryEmit(it) }
        return result
    }
}
