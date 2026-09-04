package org.bibletranslationtools.bttrecorder2.di.koin

import org.bibletranslationtools.bttrecorder2.services.UnitTargetLoader
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportProjectViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.PlaybackViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.WacsLoginViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.WacsPublishViewModel
import org.koin.core.module.dsl.factoryOf
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
    factoryOf(::WacsLoginViewModel)
    // M2: parameterized by the nav args WacsPublishRoute carries (sourceId/targetId/chapters) —
    // see Navigation.kt's `composable<WacsPublishRoute>` for the `parametersOf` call site.
    factory { (sourceId: Int, targetId: Int, chapters: List<Int>) ->
        WacsPublishViewModel(get(), get(), sourceId, targetId, chapters)
    }
    // Process-lifetime singleton so the ProjectManagement + Recorder routes share the
    // same export state (isCurrentlyExporting gates UI); auto-cleans temp dirs on init.
    single { ExportProjectViewModel() }
}
