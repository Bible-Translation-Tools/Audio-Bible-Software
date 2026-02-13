package org.bibletranslationtools.bttrecorder2.di.koin

import org.bibletranslationtools.otter.common.api.persistence.ILanguageDataSource
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IContentRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceContainerRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.WorkbookRepository
import org.bibletranslationtools.otter.common.domain.languages.LanguageDataSource
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ZipEntryTreeBuilder
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.CollectionRepository
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.ContentRepository
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.InstalledEntityRepository
import org.bibletranslationtools.otter.common.persistence.repositories.LanguageRepository
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.ResourceContainerRepository
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.ResourceMetadataRepository
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.ResourceRepository
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.TakeRepository
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.VersificationRepository
import org.wycliffeassociates.otter.jvm.workbookapp.persistence.repositories.WorkbookDescriptorRepository

// commonMain - Wrapper interface
interface AppContext

val audioModule = module {
//    single { AudioConnectionFactory() }
//    single<IAudioRecorder> { get<AudioConnectionFactory>().getRecorder() }
//    single<IAudioPlayer> { get<AudioConnectionFactory>().getPlayer() }
//    single<IWaveFileCreator> { WaveFileCreator() }
//    single { AudioDeviceProvider(DEFAULT_AUDIO_FORMAT) }
}

//val appDatabaseModule = module {
//    single {
//        val directoryProvider = get<IDirectoryProvider>()
//        AppDatabase(
//            directoryProvider.databaseDirectory.resolve(File(DB_FILE_NAME)),
//            directoryProvider
//        )
//    }
////    single<IBurritoLoader> { BurritoLoader() }
//}

val appPreferencesModule = module {
//    singleOf(::AppPreferences) { bind<IAppPreferences>() }
}

val appRepositoriesModule = module {
    singleOf(::LanguageRepository) { bind<ILanguageRepository>() }
    singleOf(::CollectionRepository) { bind<ICollectionRepository>() }
    singleOf(::ContentRepository) { bind<IContentRepository>() }
    singleOf(::ResourceRepository) { bind<IResourceRepository>() }
    singleOf(::ResourceContainerRepository) { bind<IResourceContainerRepository>() }
    singleOf(::ResourceMetadataRepository) { bind<IResourceMetadataRepository>() }
    singleOf(::TakeRepository) { bind<ITakeRepository>() }
//    singleOf(::AudioPluginRepository) { bind<IAudioPluginRepository>() }
// Explicitly define WorkbookRepository to disambiguate constructors
    single<IWorkbookRepository> {
        WorkbookRepository(
            get(), // directoryProvider
            get(), // collectionRepository
            get(), // contentRepository
            get(), // resourceRepository
            get(), // resourceMetadataRepo
            get(), // takeRepository
            get(), // languageRepository
            get()  // updateTranslationUseCase
        )
    }
    singleOf(::WorkbookDescriptorRepository) { bind<IWorkbookDescriptorRepository>() }
    singleOf(::InstalledEntityRepository) { bind<IInstalledEntityRepository>() }
//    singleOf(::AudioPluginRegistrar) { bind<IAudioPluginRegistrar>() }
//    singleOf(::AppPreferencesRepository) { bind<IAppPreferencesRepository>() }
    single<IVersificationRepository>{
        VersificationRepository(
        get(),
        get()
    ) }
//    singleOf(::LocaleDataSource) { bind<ILocaleDataSource>() }
    singleOf(::LanguageDataSource) { bind<ILanguageDataSource>() }
}

val zipEntryTreeBuilderModule = module {
    singleOf(::ZipEntryTreeBuilder) { bind<IZipEntryTreeBuilder>() }
}

val metadataModule = module {
//    single<IAppInfo> { AppInfo() }
}

val authModule = module {
//    single<AuthProvider> { WacsIdAuthority() }
}

val commonModules = listOf(
    *implicitModules.toTypedArray(),
    commonAudioModule,
//    appDatabaseModule,
    appPreferencesModule,
//    directoryProviderModule,
    appRepositoriesModule,
    zipEntryTreeBuilderModule,
    metadataModule,
    authModule
)
