package org.bibletranslationtools.orature.ui.viewmodels

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode

/**
 * An import-result notification shown at the app root as a snackbar (JVM: NotificationViewData). When
 * [workbookDescriptorId] is present the snackbar offers an "Open Book" action that navigates to that
 * book (JVM: the notification's "Open Book" action → selectBook).
 */
data class OratureImportNotification(
    val message: String,
    val workbookDescriptorId: Int? = null,
    val mode: ProjectMode? = null
)

/**
 * A tiny app-scoped bus so a successful import (from the global Add Files drawer, which lives in the
 * shell) can tell the home screen to reload its project list. Decouples the import VM from the home
 * VM — they're separate instances. Registered as a Koin single.
 */
class OratureImportEvents {
    private val _imported = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val imported: SharedFlow<Unit> = _imported.asSharedFlow()

    // Import result messages, shown by the app root as a snackbar (JVM: SnackbarHandler.showNotification
    // at the app root — success/failure were toasts, not dialogs).
    private val _notifications = MutableSharedFlow<OratureImportNotification>(extraBufferCapacity = 4)
    val notifications: SharedFlow<OratureImportNotification> = _notifications.asSharedFlow()

    fun notifyImported() {
        _imported.tryEmit(Unit)
    }

    fun notify(notification: OratureImportNotification) {
        _notifications.tryEmit(notification)
    }
}
