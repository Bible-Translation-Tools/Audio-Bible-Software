package org.bibletranslationtools.bttrecorder2.di.koin

import org.bibletranslationtools.bttrecorder2.exports.WriteNarrationForExport
import org.bibletranslationtools.bttrecorder2.imports.ImportNarrationAsTakes
import org.bibletranslationtools.bttrecorder2.migration.InitializeModeSources
import org.bibletranslationtools.bttrecorder2.migration.MigrateLegacyRecorderProjects
import org.bibletranslationtools.bttrecorder2.migration.StageLegacyBackup
import org.bibletranslationtools.bttrecorder2.services.UnitTargetLoader
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportProjectViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.PlaybackViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListViewModel
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** App-context marker; the android app binds a concrete Context-backed impl. */
interface AppContext

/**
 * The recorder app's own ViewModels + app services. Each app owns its VM module (NOT
 * shared); composed in startKoin alongside :shared's sharedCommonModules and the platform
 * (sharedDesktopModules / sharedAndroidModules) + directory provider.
 *
 * The app's own non-UI code lives in `bttrecorder2.services` — [UnitTargetLoader] here, plus
 * `InsertRecorder`, which PlaybackViewModel constructs directly rather than resolving. Those
 * are NOT `otter.common.domain` use cases (those come from :shared and are bound there); they
 * are this app's own layer between its screens and that domain.
 */
val recorderViewModelModule = module {
    // Shared by the Recorder and Playback screens: navigation args -> the flat
    // chapter-then-chunks list both page through.
    factoryOf(::UnitTargetLoader)
    single { ProjectManagementViewModel() }
    single { ProjectCreationViewModel() }
    single { ChapterListViewModel() }
    single { UnitListViewModel() }
    factoryOf(::RecorderViewModel)
    factoryOf(::PlaybackViewModel)
    // Process-lifetime singleton so the ProjectManagement + Recorder routes share the
    // same export state (isCurrentlyExporting gates UI); auto-cleans temp dirs on init.
    single { ExportProjectViewModel() }
}

/**
 * One-time migration of the legacy Android BTT-Recorder's projects, chained after `InitializeApp`
 * by `SplashScreenViewModel`.
 *
 * Separate from [recorderViewModelModule] because it depends on a `LegacyRecorderStore`, which only
 * the platform modules can bind, and `RecorderUiTestHarness` composes the ViewModel module without
 * them. `SplashScreenViewModel` resolves this module's migration optionally, so a Koin graph lacking
 * it skips migration rather than failing.
 */
val recorderMigrationModule = module {
    // Imports the mode-specific ULB sources. Not an initializer: migration invokes it once it has
    // found legacy data, so a clean install does no work.
    singleOf(::InitializeModeSources)
    // Writes a legacy project out as a dialect backup for the app's own import to read.
    singleOf(::StageLegacyBackup)
    singleOf(::MigrateLegacyRecorderProjects).bind<Installable>()
}

/**
 * Converts between the narration audio Orature reads and the per-unit takes this recorder records —
 * [ImportNarrationAsTakes] on the way in, [WriteNarrationForExport] on the way out.
 *
 * Its own module because [recorderViewModelModule] binds ViewModels and [recorderMigrationModule]
 * needs a platform `LegacyRecorderStore`. These two depend only on use cases :shared binds, so every
 * graph composes this one — including `RecorderUiTestHarness`, which lets the ViewModels resolve
 * them as required rather than optional dependencies.
 */
val recorderNarrationModule = module {
    factoryOf(::ImportNarrationAsTakes)
    factoryOf(::WriteNarrationForExport)
}
