package org.bibletranslationtools.orature.di

import org.bibletranslationtools.orature.plugins.OraturePluginStore
import org.bibletranslationtools.orature.services.OratureImportEvents
import org.bibletranslationtools.orature.services.OratureProjectEvents
import org.bibletranslationtools.orature.services.OratureProjectDeletion
import org.bibletranslationtools.orature.services.OratureVerseMarkerEditor
import org.bibletranslationtools.orature.services.OratureWorkbookDataStore
import org.bibletranslationtools.orature.ui.OratureNavigationLock
import org.bibletranslationtools.orature.ui.narration.OratureNarrationFactory
import org.bibletranslationtools.orature.ui.viewmodels.OratureHomeViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureProjectWizardViewModel
import org.bibletranslationtools.orature.ui.viewmodels.OratureSettingsViewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/**
 * Orature's own ViewModels and app services (NOT shared with the recorder). Composed in
 * startKoin alongside :shared's sharedCommonModules and the platform module + directory
 * provider, mirroring the recorder's recorderViewModelModule pattern.
 *
 * Most of what this binds is not a ViewModel: the app-scoped singletons below live in
 * `orature.services` — coordinators, event buses and open-project state that outlive any one
 * screen. They are NOT `otter.common.domain` use cases (those come from :shared and are
 * bound there); they are this app's own layer between its screens and that domain.
 */
val oratureViewModelModule = module {
    single { OratureHomeViewModel() }
    // App-scoped bus so a successful import (global Add Files drawer in the shell) refreshes home.
    single { OratureImportEvents() }
    single { OratureProjectEvents() }
    // App-scoped project-deletion coordinator + pending-delete counter (guards project creation).
    single { OratureProjectDeletion() }
    // App-scoped registry of external-editor plugins (Settings → plugins; desktop-only).
    single { OraturePluginStore() }
    // App-scoped handoff for the built-in Verse Marker editor (JVM: the marker plugin's parameter
    // scope). A host compiles the chapter take, fills a Request, then navigates to the marker route.
    single { OratureVerseMarkerEditor() }
    // App-scoped in-app navigation lock (JVM: the OS window-close guard, adapted) — set while an
    // external editor plugin has a take open; read by OratureRootShell to disable the nav rail.
    single { OratureNavigationLock() }
    // Shared open-project state (JVM: WorkbookDataStore) — written by the narration VM,
    // read by the mode-page components. Single so it survives across the mode screens.
    single { OratureWorkbookDataStore(get(), get()) }
    // Constructs a Narration for (workbook, chapter) — the port's stand-in for the JVM's
    // Dagger NarrationFactory (resolves deps: directoryProvider, splitAudioOnCues,
    // audioFileUtils, audioBouncer, recorder + player connection factories).
    single { OratureNarrationFactory(get(), get(), get(), get(), get(), get()) }
    // Factory: each drawer open gets a fresh VM; it re-scans devices and observes prefs.
    factory { OratureSettingsViewModel(get(), get(), get()) }
    // Factory: the wizard resolves the shared use-cases/repos via KoinComponent; the caller
    // passes the onComplete callback (home reload) as a param. Each wizard entry is fresh.
    factory { (onComplete: () -> Unit) -> OratureProjectWizardViewModel(onComplete) }
}

/** Convenience for resolving the wizard VM with its onComplete callback. */
fun wizardViewModelParams(onComplete: () -> Unit) = parametersOf(onComplete)
