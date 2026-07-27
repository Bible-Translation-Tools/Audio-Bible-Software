package org.bibletranslationtools.bttrecorder2.di.koin

import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ExportProjectViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.PlaybackViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

/** App-context marker; the android app binds a concrete Context-backed impl. */
interface AppContext

/**
 * The recorder app's own ViewModels + app-scoped singletons. Each app owns its VM
 * module (NOT shared); composed in startKoin alongside :shared's sharedCommonModules
 * and the platform (sharedDesktopModules / sharedAndroidModules) + directory provider.
 */
val recorderViewModelModule = module {
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
