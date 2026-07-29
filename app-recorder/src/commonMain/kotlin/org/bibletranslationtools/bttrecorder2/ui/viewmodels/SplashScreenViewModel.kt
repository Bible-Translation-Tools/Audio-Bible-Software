package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.reactivex.Completable
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SplashScreenViewModel(): ViewModel(), KoinComponent {

    //@Inject
    private val initApp: InitializeApp by inject()

    var progressTitle by mutableStateOf("")
    var progressBody by mutableStateOf("")
    var progress by mutableStateOf(0.0)

    fun initApp(): Completable {
        initApp.toString()
        return initApp.initApp()
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
}