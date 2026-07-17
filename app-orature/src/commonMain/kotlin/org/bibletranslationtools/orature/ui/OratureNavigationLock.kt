package org.bibletranslationtools.orature.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped lock (JVM: `RootView`'s `shouldBlockWindowCloseRequest`/`externalPluginOpenedProperty`
 * — there it blocks the OS window close while an external plugin is open) — a Koin `single` so it
 * can be written from deep inside a step's ViewModel (currently
 * [org.bibletranslationtools.orature.ui.viewmodels.OratureChapterReviewViewModel]) and read both
 * by [OratureRootShell] (to disable the nav rail) and by the desktop `main()`'s window
 * `onCloseRequest` (which lives outside Compose entirely, hence `getKoin().get()` there rather
 * than [org.koin.compose.koinInject]).
 */
class OratureNavigationLock {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    // JVM: `showNotification(messages["applicationCloseBlocked"], snackBarRoot)` — the OS close
    // handler isn't Composable, so it just emits here; the Compose tree (OratureApp) shows the
    // actual snackbar.
    private val _closeBlockedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeBlockedEvents: SharedFlow<Unit> = _closeBlockedEvents.asSharedFlow()

    fun lock() { _locked.value = true }
    fun unlock() { _locked.value = false }

    fun notifyCloseBlocked() {
        _closeBlockedEvents.tryEmit(Unit)
    }
}
