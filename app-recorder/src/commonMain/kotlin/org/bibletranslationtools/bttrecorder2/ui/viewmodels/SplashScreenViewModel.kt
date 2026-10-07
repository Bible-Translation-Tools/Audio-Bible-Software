package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.reactivex.Completable
import org.bibletranslationtools.bttrecorder2.legacymigration.LegacyMigrationStartupStep
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SplashScreenViewModel(): ViewModel(), KoinComponent {

    //@Inject
    private val initApp: InitializeApp by inject()

    var progressTitle by mutableStateOf("")
    var progressBody by mutableStateOf("")
    var progress by mutableStateOf(0.0)

    /** While old BTT-Recorder projects are being moved: how many books are done, of how many. */
    var legacyMigration by mutableStateOf<Pair<Int, Int>?>(null)

    fun initApp(): Completable {
        initApp.toString()
        return initApp.initApp()
            //.doOnError { logger.error("Error initializing app: ", it) }
            .doOnNext { status ->
                LegacyMigrationStartupStep.progressOf(status)?.let {
                    legacyMigration = it
                    return@doOnNext
                }
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
}