package org.bibletranslationtools.orature.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.reactivex.disposables.Disposable
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.bibletranslationtools.shared.logging.logFailure
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Drives app startup: runs the shared backend's [InitializeApp] (DB migration +
 * versification/content/language seeding) and surfaces its progress. InitializeApp is
 * provided by :shared's sharedCommonModules, so this needs no app-specific Koin wiring.
 *
 * The subscription is owned HERE rather than by a LaunchedEffect in the navigation graph.
 * Disposing an in-flight RxJava chain interrupts the thread running it, and InitializeApp is a
 * chain of blockingAwait/blockingGet calls — so a dispose part-way through surfaces as
 * `RuntimeException: InterruptedException` out of whichever initializer was mid-flight, which then
 * has nowhere to be delivered and takes the process down. A composable leaves composition on every
 * configuration change; a ViewModel does not, so init now runs to completion across an activity
 * recreation instead of being killed by one.
 *
 * The race only became reachable once a project with source audio made fetchProjects() slow enough
 * to still be running when the recreation happened.
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

    private var subscription: Disposable? = null

    /**
     * Starts initialization, at most once for the lifetime of this ViewModel. Safe to call from a
     * LaunchedEffect that re-runs — a second call while the first is in flight is a no-op.
     */
    fun startInit() {
        if (subscription != null) return
        subscription = initApp.initApp()
            .doOnNext { status ->
                status.titleKey?.let { progressTitle = it; progressBody = "" }
                status.subTitleKey?.let { progressBody = it }
                status.percent?.let { progress = it }
            }
            .ignoreElements()
            .subscribe(
                { initComplete = true },
                { e ->
                    logFailure(this, "initializing the app", e)
                    // Let the user reach Home rather than sitting on the splash forever; a failed
                    // seed step is recoverable in a way a dead screen is not.
                    initComplete = true
                }
            )
    }

    override fun onCleared() {
        // Only reached when the ViewModel is genuinely finished — not on configuration change.
        subscription?.dispose()
        subscription = null
        super.onCleared()
    }
}
