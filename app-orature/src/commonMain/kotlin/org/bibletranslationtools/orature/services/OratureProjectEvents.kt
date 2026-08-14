package org.bibletranslationtools.orature.services

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An app-scoped bus telling the home screen that ONE book's progress may have changed.
 *
 * The home list is built once, in the ViewModel's `init`, and the home ViewModel survives
 * navigating into a book — it is sitting on the back stack the whole time. So nothing recomputed
 * progress on the way back out: a chapter narrated end to end still showed the book at 0%, and
 * stayed there until something else forced a full reload. The export dialog, which opens the
 * workbook fresh, disagreed with the ring right next to it.
 *
 * Scoped to a single descriptor rather than "reload everything" because a full reload is genuinely
 * expensive here: [org.bibletranslationtools.otter.common.persistence.repositories.WorkbookDescriptorRepository]
 * constructs a Workbook per book to answer the progress question, and a device with every book of
 * the Bible listed pays that per book. Leaving one book can only have changed that one book.
 *
 * Registered as a Koin single, like [OratureImportEvents]. Kept separate from it because that one
 * means "the project LIST changed" (a book was added) — this one means "a book's contents changed".
 */
class OratureProjectEvents {

    private val _progressChanged = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** Emits the workbook descriptor id whose progress should be recomputed. */
    val progressChanged: SharedFlow<Int> = _progressChanged.asSharedFlow()

    /**
     * Signals that the user has finished with [workbookDescriptorId] and its progress should be
     * re-read. Non-suspending on purpose: it is called from `ViewModel.onCleared`, where the
     * caller's own scope is already cancelled and there is nothing left to launch from.
     */
    fun notifyProgressChanged(workbookDescriptorId: Int) {
        _progressChanged.tryEmit(workbookDescriptorId)
    }
}
