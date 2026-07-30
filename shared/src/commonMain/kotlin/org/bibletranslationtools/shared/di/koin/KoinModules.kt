package org.bibletranslationtools.shared.di.koin

import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
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
import org.bibletranslationtools.otter.common.persistence.repositories.WorkbookRepository
import org.bibletranslationtools.otter.common.domain.languages.LanguageDataSource
import org.bibletranslationtools.otter.common.domain.project.GlSourceCatalog
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.ZipEntryTreeBuilder
import org.bibletranslationtools.shared.content.ComposeBundledContentSource
import org.bibletranslationtools.shared.domain.SourceAudioImporter
import org.bibletranslationtools.shared.preferences.DataStoreAppPreferences
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.IFileIOFactory
import org.bibletranslationtools.otter.common.api.persistence.IProjectDirectories
import org.bibletranslationtools.otter.common.api.persistence.IResourceContainerDirectories
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.bibletranslationtools.otter.common.persistence.repositories.CollectionRepository
import org.bibletranslationtools.otter.common.persistence.repositories.ContentRepository
import org.bibletranslationtools.otter.common.persistence.repositories.InstalledEntityRepository
import org.bibletranslationtools.otter.common.persistence.repositories.LanguageRepository
import org.bibletranslationtools.otter.common.persistence.repositories.ResourceContainerRepository
import org.bibletranslationtools.otter.common.persistence.repositories.ResourceMetadataRepository
import org.bibletranslationtools.otter.common.persistence.repositories.ResourceRepository
import org.bibletranslationtools.otter.common.persistence.repositories.TakeRepository
import org.bibletranslationtools.otter.common.persistence.repositories.VersificationRepository
import org.bibletranslationtools.otter.common.persistence.repositories.WorkbookDescriptorRepository

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
    single<IAppPreferences> {
        val dir = get<IAppDirectories>().getAppDataDirectory("preferences")
        dir.mkdirs()
        DataStoreAppPreferences(dir.absolutePath)
    }
    single { SourceAudioImporter(get(), get(), get()) }
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

// Bundled content (GL source zips, langnames, source manifests, versification) reaches the
// backend through IBundledContentSource so that domain/ and initialization/ never import the
// Compose Res object. Both are singles: GlSourceCatalog parses lazily and caches, matching
// the process-wide caching the old ImportProjectUseCase.Companion lazies provided.
val bundledContentModule = module {
    single<IBundledContentSource> { ComposeBundledContentSource() }
    singleOf(::GlSourceCatalog)
}

// The five narrow directory ports, each bound to the one IDirectoryProvider the app supplies.
//
// Consumers depend on the slice they call (a use case that only stages temp audio takes
// ITempFileProvider, not all 27 members), while there is still exactly one provider instance
// per process — these delegate rather than construct, so `assertSame` holds across all of them.
// Without these bindings every narrowed constructor would fail to resolve at first use, which
// with Koin's lazy `by inject()` means at runtime rather than at startup.
val directoryPortsModule = module {
    single<IAppDirectories> { get<IDirectoryProvider>() }
    single<ITempFileProvider> { get<IDirectoryProvider>() }
    single<IProjectDirectories> { get<IDirectoryProvider>() }
    single<IResourceContainerDirectories> { get<IDirectoryProvider>() }
    single<IFileIOFactory> { get<IDirectoryProvider>() }
}

val metadataModule = module {
//    single<IAppInfo> { AppInfo() }
}

val authModule = module {
//    single<AuthProvider> { WacsIdAuthority() }
}

// The shared, platform-agnostic Koin modules both apps compose. Backend use-cases +
// audio + prefs + repositories only — NO ViewModels (each app owns its own) and NO
// platform pieces (directory provider / DB / device audio live in the platform lists).
val sharedCommonModules = listOf(
    implicitCommonModule,
    commonAudioModule,
    appPreferencesModule,
    appRepositoriesModule,
    zipEntryTreeBuilderModule,
    bundledContentModule,
    directoryPortsModule,
    metadataModule,
    authModule
)
