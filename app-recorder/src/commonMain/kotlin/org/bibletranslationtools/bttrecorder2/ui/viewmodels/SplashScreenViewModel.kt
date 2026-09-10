package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.reactivex.Completable
import io.reactivex.Observable
import org.bibletranslationtools.bttrecorder2.migration.MigrateLegacyRecorderProjects
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SplashScreenViewModel(): ViewModel(), KoinComponent {

    //@Inject
    private val initApp: InitializeApp by inject()

    /**
     * The one-time migration of legacy Android BTT-Recorder projects, run after [initApp]. It
     * imports the ULB mode sources itself, once it has found legacy data.
     */
    private val migrateLegacyProjects: MigrateLegacyRecorderProjects by inject()

    var progressTitle by mutableStateOf("")
    var progressBody by mutableStateOf("")
    var progress by mutableStateOf(0.0)

    fun initApp(): Completable {
        initApp.toString()
        return initApp.initApp()
            // After initApp, since migration derives projects from imported source text and so
            // needs the source and project tables to exist.
            .concatWith(installable(migrateLegacyProjects))
            //.doOnError { logger.error("Error initializing app: ", it) }
            .doOnNext { status ->
                status.titleKey?.let { title ->
                    progressTitle = String.format(title, status.titleMessage ?: "")
                    progressBody = ""
                }
                status.subTitleKey?.let { body ->
                    progressBody = (String.format(body, status.subTitleMessage ?: ""))
                }
                status.percent?.let { progress = it }
            }
            .ignoreElements()
    }

    /**
     * Runs an [Installable] as a progress stream, reusing the splash's existing plumbing.
     *
     * The outcome is deliberately not surfaced: migration is silent, and the log and its ledger are
     * the record. Errors are swallowed so a failure cannot block startup, leaving the work for the
     * next launch.
     */
    private fun installable(step: Installable?): Observable<ProgressStatus> {
        if (step == null) return Observable.empty()
        return Observable
            .create { emitter ->
                step.exec(emitter).blockingAwait()
                emitter.onComplete()
            }
            .onErrorResumeNext(Observable.empty())
    }
}
