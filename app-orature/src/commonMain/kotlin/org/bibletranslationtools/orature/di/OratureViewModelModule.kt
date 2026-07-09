package org.bibletranslationtools.orature.di

import org.bibletranslationtools.orature.ui.viewmodels.OratureHomeViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectWizardViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureSettingsViewModel
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Orature's own ViewModels (NOT shared with the recorder). Composed in startKoin
 * alongside :shared's sharedCommonModules and the platform module + directory provider,
 * mirroring the recorder's recorderViewModelModule pattern.
 */
val oratureViewModelModule = module {
    single { OratureHomeViewModel() }
    // Shared open-project state (JVM: WorkbookDataStore) — written by the narration VM,
    // read by the mode-page components. Single so it survives across the mode screens.
    single { OratureWorkbookDataStore(get()) }
    // Factory: each drawer open gets a fresh VM; it re-scans devices and observes prefs.
    factory { OratureSettingsViewModel(get(), get(), get()) }
    // Factory: the wizard resolves the shared use-cases/repos via KoinComponent; the caller
    // passes the onComplete callback (home reload) as a param. Each wizard entry is fresh.
    factory { (onComplete: () -> Unit) -> OratureProjectWizardViewModel(onComplete) }
}

/** Convenience for resolving the wizard VM with its onComplete callback. */
fun wizardViewModelParams(onComplete: () -> Unit) = parametersOf(onComplete)
