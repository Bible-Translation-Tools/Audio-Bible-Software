package org.bibletranslationtools.orature.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.bibletranslationtools.shared.logging.logFailure
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Drives app startup: runs the shared backend's [InitializeApp] (DB migration +
 * versification/content/language seeding) and surfaces its progress. InitializeApp is
 * provided by :shared's sharedCommonModules, so this needs no app-specific Koin wiring.
 *
 * The init job is owned HERE, in the ViewModel's scope, rather than by a LaunchedEffect in the
 * navigation graph. A composable leaves composition on every configuration change; a ViewModel
 * does not, so init runs to completion across an activity recreation instead of being canceled
 * part-way and started again.
 */
class OratureSplashViewModel : ViewModel(), KoinComponent {

    private val initApp: InitializeApp by inject()

    var progressTitle by mutableStateOf("")
        private set
    var progressBody by mutableStateOf("")
        private set
    var progress by mutableStateOf(0.0)
        private set

    /** Flips once init finishes; the splash route navigates onward when it does. */
    var initComplete by mutableStateOf(false)
        private set

    private var initJob: Job? = null

    /**
     * Starts initialization, at most once for the lifetime of this ViewModel. Safe to call from a
     * LaunchedEffect that re-runs — a second call while the first is in flight is a no-op.
     */
    fun startInit() {
        if (initJob != null) return
        initJob = launchLogged {
            runCatching { initApp.initApp(::onProgress) }.onFailure {
                if (it is CancellationException) {
                    ensureActive()
                }
                logFailure(this@OratureSplashViewModel, "initializing the app", it)
            }
            // Let the user reach Home rather than sitting on the splash forever; a failed
            // seed step is recoverable in a way a dead screen is not.
            initComplete = true
        }
    }

    private fun onProgress(status: ProgressStatus) {
        status.titleKey?.let { progressTitle = it; progressBody = "" }
        status.subTitleKey?.let { progressBody = it }
        status.percent?.let { progress = it }
    }
}
