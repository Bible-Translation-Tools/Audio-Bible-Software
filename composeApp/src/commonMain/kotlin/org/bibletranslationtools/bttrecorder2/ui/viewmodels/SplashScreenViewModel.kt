package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.reactivex.Completable
import org.bibletranslationtools.otter.common.di.DependencyProvider
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import javax.inject.Inject

class SplashScreenViewModel(
    dependencyProvider: DependencyProvider
): ViewModel() {

    @Inject
    lateinit var initApp: InitializeApp

    var progressTitle by mutableStateOf("")
    var progressBody by mutableStateOf("")
    var progress by mutableStateOf(0.0)

    init {
        dependencyProvider.inject(this)
    }

    fun initApp(): Completable {
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