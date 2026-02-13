package org.bibletranslationtools.bttrecorder2.di.koin

import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectManagementViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SplashScreenViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.UnitListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.RecorderViewModel
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.domain.audio.AudioBouncer
import org.bibletranslationtools.otter.common.domain.audio.AudioConverter
import org.bibletranslationtools.otter.common.domain.audio.AudioExporter
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.collections.CreateTranslation
import org.bibletranslationtools.otter.common.domain.collections.DeleteProject
import org.bibletranslationtools.otter.common.domain.collections.DeleteTranslation
import org.bibletranslationtools.otter.common.domain.collections.UpdateProject
import org.bibletranslationtools.otter.common.domain.collections.UpdateTranslation
import org.bibletranslationtools.otter.common.domain.content.ChapterTranslationBuilder
import org.bibletranslationtools.otter.common.domain.content.ConcatenateAudio
import org.bibletranslationtools.otter.common.domain.content.CreateChunks
import org.bibletranslationtools.otter.common.domain.content.ResetChunks
import org.bibletranslationtools.otter.common.domain.content.TakeCreator
import org.bibletranslationtools.otter.common.domain.narration.AudioFileUtils
import org.bibletranslationtools.otter.common.domain.narration.NarrationHistory
import org.bibletranslationtools.otter.common.domain.narration.PcmTakeTransformer
import org.bibletranslationtools.otter.common.domain.narration.SplitAudioOnCues
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.ProjectCompletionStatus
import org.bibletranslationtools.otter.common.domain.project.exporter.AudioProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.BackupProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.SourceProjectExporter
import org.bibletranslationtools.otter.common.domain.project.importer.BurritoImporter
import org.bibletranslationtools.otter.common.domain.project.importer.BurritoImporterFactory
import org.bibletranslationtools.otter.common.domain.project.importer.ExistingSourceImporter
import org.bibletranslationtools.otter.common.domain.project.importer.IProjectImporterFactory
import org.bibletranslationtools.otter.common.domain.project.importer.NewSourceImporter
import org.bibletranslationtools.otter.common.domain.project.importer.OngoingProjectImporter
import org.bibletranslationtools.otter.common.domain.project.importer.RCImporterFactory
import org.bibletranslationtools.otter.common.domain.project.importer.TsImporterFactory
import org.bibletranslationtools.otter.common.domain.project.importer.TstudioImporter
import org.bibletranslationtools.otter.common.domain.resourcecontainer.DeleteResourceContainer
import org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.BurritoToResourceContainerConverter
import org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.ScriptureBurritoUtils
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.VersificationTreeBuilder
import org.bibletranslationtools.otter.common.initialization.InitializeApp
import org.bibletranslationtools.otter.common.initialization.InitializeLanguages
import org.bibletranslationtools.otter.common.initialization.InitializeProjects
import org.bibletranslationtools.otter.common.initialization.InitializeSources
import org.bibletranslationtools.otter.common.initialization.InitializeTakeRepository
import org.bibletranslationtools.otter.common.initialization.InitializeTranslations
import org.bibletranslationtools.otter.common.initialization.InitializeUlb
import org.bibletranslationtools.otter.common.initialization.InitializeVersification
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.AudioPluginDataMapper
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.CollectionMapper
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.LanguageMapper
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.MarkerMapper
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.ResourceMetadataMapper
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.mapping.TranslationMapper

val implicitCommonModule = module {
    // Collections
    factoryOf(::DeleteTranslation)
    factoryOf(::CreateTranslation)
    factoryOf(::CreateProject)
    factoryOf(::UpdateTranslation)
    factoryOf(::UpdateProject)
    factoryOf(::DeleteProject)

    // Audio
    factoryOf(::AudioExporter)
    factoryOf(::AudioBouncer)
    factoryOf(::AudioConverter)

    // Project
    factoryOf(::ImportProjectUseCase)
//    factoryOf(::ProjectFormatIdentifier)

    // Exporters
    factoryOf(::AudioProjectExporter)
    factoryOf(::BackupProjectExporter)
    factoryOf(::SourceProjectExporter)

    // Importers
    factoryOf(::NewSourceImporter)
    factoryOf(::ExistingSourceImporter)
    factoryOf(::TsImporterFactory)
    factoryOf(::OngoingProjectImporter)
    factoryOf(::BurritoImporterFactory)
    factoryOf(::BurritoImporter)
    factoryOf(::TstudioImporter)
    factoryOf(::RCImporterFactory)
    factoryOf(::InitializeApp)

    // Content
    factoryOf(::ChapterTranslationBuilder)
    factoryOf(::TakeCreator)
    factoryOf(::ConcatenateAudio)
//    factoryOf(::PluginActions)
    factoryOf(::ResetChunks)
    factoryOf(::CreateChunks)

    // Resource Container
    factoryOf(::DeleteResourceContainer)
    factoryOf(::VersificationTreeBuilder)
    factoryOf(::BurritoToResourceContainerConverter)
    factoryOf(::ScriptureBurritoUtils)

    // Plugins
//    factoryOf(::LaunchPlugin)

    // Mappers
    factoryOf(::AudioPluginDataMapper)
    factoryOf(::CollectionMapper)
    factoryOf(::LanguageMapper)
    factoryOf(::MarkerMapper)
    factoryOf(::ResourceMetadataMapper)
    factoryOf(::TranslationMapper)

    // Other Implicit Dependencies
    factoryOf(::ProjectCompletionStatus)
    factoryOf(::NarrationHistory)

    // Narration
    factoryOf(::PcmTakeTransformer)
    factoryOf(::AudioFileUtils)
    factoryOf(::SplitAudioOnCues)

    factoryOf(::InitializeVersification)
    factoryOf(::InitializeSources)
    factoryOf(::InitializeLanguages)
    factoryOf(::InitializeUlb)
    factoryOf(::InitializeTakeRepository)
    factoryOf(::InitializeProjects)
    factoryOf(::InitializeTranslations)
}

val implicitViewModelModule = module {
//    factoryOf(::RootViewModel)
    //single<SplashScreenViewModel> { SplashScreenViewModel(get()) }
    singleOf(::ProjectManagementViewModel)
    singleOf(::ProjectCreationViewModel)
    singleOf(::ChapterListViewModel)
    singleOf(::UnitListViewModel)
    factoryOf(::RecorderViewModel)
//    factoryOf(::HomePageViewModel2)
//    factoryOf(::AddPluginViewModel)
//
//    singleOf(::AudioDataStore)
//    singleOf(::AppPreferencesStore)
//
//    factoryOf(::AudioPluginViewModel)
//    factoryOf(::SettingsViewModel)
//    factoryOf(::ImportProjectViewModel)
//    factoryOf(::ImportAudioViewModel)
//    factoryOf(::TranslationViewModel2)
//    factoryOf(::ProjectWizardViewModel)
//    factoryOf(::AppInfoViewModel)
//    factoryOf(::ConsumeViewModel)
//    factoryOf(::ChunkingViewModel)
//    factoryOf(::BlindDraftViewModel)
//    factoryOf(::PeerEditViewModel)
//    factoryOf(::ChapterReviewViewModel)
//    factoryOf(::RecorderViewModel)
//    factoryOf(::ExportProjectViewModel)
//    factoryOf(::AudioWorkspaceViewModel)
//    factoryOf(::NarrationViewModel)
//    factoryOf(::NarrationHeaderViewModel)
}

val implicitModules = listOf(
    implicitCommonModule,
    implicitViewModelModule
)


