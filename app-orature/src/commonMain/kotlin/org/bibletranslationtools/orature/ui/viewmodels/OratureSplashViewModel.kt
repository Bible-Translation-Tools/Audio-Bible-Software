package org.bibletranslationtools.orature.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.reactivex.Completable
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Drives app startup: runs the shared backend's [InitializeApp] (DB migration +
 * versification/content/language seeding) and surfaces its progress. InitializeApp is
 * provided by :shared's sharedCommonModules, so this needs no app-specific Koin wiring.
 */
class OratureSplashViewModel : ViewModel(), KoinComponent {

    private val initApp: InitializeApp by inject()

    var progressTitle by mutableStateOf("")
        private set
    var progressBody by mutableStateOf("")
        private set
    var progress by mutableStateOf(0.0)
        private set

    fun initApp(): Completable =
        initApp.initApp()
            .doOnNext { status ->
                status.titleKey?.let { progressTitle = it; progressBody = "" }
                status.subTitleKey?.let { progressBody = it }
                status.percent?.let { progress = it }
            }
            .ignoreElements()
}
