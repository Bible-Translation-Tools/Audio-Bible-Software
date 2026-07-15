package org.bibletranslationtools.orature.ui.viewmodels

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A tiny app-scoped bus so a successful import (from the global Add Files drawer, which lives in the
 * shell) can tell the home screen to reload its project list. Decouples the import VM from the home
 * VM — they're separate instances. Registered as a Koin single.
 */
class OratureImportEvents {
    private val _imported = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val imported: SharedFlow<Unit> = _imported.asSharedFlow()

    fun notifyImported() {
        _imported.tryEmit(Unit)
    }
}
