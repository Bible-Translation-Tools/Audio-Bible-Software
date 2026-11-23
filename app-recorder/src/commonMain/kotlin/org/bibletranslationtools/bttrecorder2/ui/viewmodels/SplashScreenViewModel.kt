package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Drives app startup: runs the shared backend's [InitializeApp] and surfaces its progress.
 *
 * The init job is owned here, in the ViewModel's scope, rather than by a LaunchedEffect in the
 * navigation graph: a composable leaves composition when the activity is recreated, a ViewModel
 * does not, so init runs to completion instead of being cancelled part-way and started again.
 */
class SplashScreenViewModel: ViewModel(), KoinComponent {

    private val initApp: InitializeApp by inject()

    var progressTitle by mutableStateOf("")
    var progressBody by mutableStateOf("")
    var progress by mutableStateOf(0.0)

    /** Flips once init finishes; the splash route navigates onward when it does. */
    var initComplete by mutableStateOf(false)
        private set

    private var initJob: Job? = null

    /**
     * Starts initialization, at most once for the lifetime of this ViewModel. Safe to call from a
     * LaunchedEffect that re-runs: a second call is a no-op.
     */
    fun startInit() {
        if (initJob != null) return
        initJob = launchLogged {
            runCatching { initApp.initApp(::onProgress) }.onFailure {
                if (it is CancellationException) {
                    ensureActive()
                }
                logFailure("initializing the app", it)
            }
            // As in Orature: a failed init step is logged and the user still reaches the main
            // menu, rather than sitting on the splash forever.
            initComplete = true
        }
    }

    private fun onProgress(status: ProgressStatus) {
        status.titleKey?.let { title ->
            progressTitle = String.format(title, status.titleMessage ?: "")
            progressBody = ""
        }
        status.subTitleKey?.let { body ->
            progressBody = (String.format(body, status.subTitleMessage ?: ""))
        }
        status.percent?.let { progress = it }
    }
}
