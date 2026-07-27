package org.bibletranslationtools.bttrecorder2.di.kotlin_inject

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
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

import javax.inject.Singleton

@Component
interface AudioModule {
//    companion object {
//        val audioConnectionFactory = AudioConnectionFactory()
//    }

//    @Provides
//    fun providesRecorder(): IAudioRecorder = audioConnectionFactory.getRecorder()
//
//    @Provides
//    fun providesPlayer(): IAudioPlayer = audioConnectionFactory.getPlayer()

//    @Provides
//    fun providesConnectionFactory(): AudioConnectionFactory = audioConnectionFactory

//    @Provides
//    fun providesWavCreator(): IWaveFileCreator = WaveFileCreator()

//    @Provides
//    @Singleton
//    fun providesAudioDevice(): AudioDeviceProvider = AudioDeviceProvider(DEFAULT_AUDIO_FORMAT)
}

@Component
interface AppDatabaseModule {
//    @Provides
//    @Singleton
//    fun providesAppDatabase(directoryProvider: IDirectoryProvider): AppDatabase {
//        return AppDatabase(
//            directoryProvider.databaseDirectory.resolve(File(DB_FILE_NAME)),
//            directoryProvider
//        )
//    }

//    @Provides
//    fun providesBurritoLoader(): IBurritoLoader = BurritoLoader()
}

@Component
interface AppPreferencesModule {
//    @Provides
//    fun inject(preferences: AppPreferences): IAppPreferences = preferences
}

@Component
interface DirectoryProviderModule {
//    @Provides
//    fun providesDirectoryProvider(): IDirectoryProvider = DirectoryProvider(OratureInfo.SUITE_NAME)
}

@Component
interface AppRepositoriesModule {
    @Provides @Singleton fun providesLanguageRepo(impl: LanguageRepository): ILanguageRepository = impl
    @Provides @Singleton fun providesCollectionRepo(impl: CollectionRepository): ICollectionRepository = impl
    @Provides @Singleton fun providesContentRepo(impl: ContentRepository): IContentRepository = impl
    @Provides @Singleton fun providesResourceRepo(impl: ResourceRepository): IResourceRepository = impl
    @Provides @Singleton fun providesResourceContainerRepo(impl: ResourceContainerRepository): IResourceContainerRepository = impl
    @Provides @Singleton fun providesResourceMetadataRepo(impl: ResourceMetadataRepository): IResourceMetadataRepository = impl
    @Provides @Singleton fun providesTakeRepo(impl: TakeRepository): ITakeRepository = impl
//    @Provides @Singleton fun providesPluginRepo(impl: AudioPluginRepository): IAudioPluginRepository = impl
    @Provides @Singleton fun providesWorkbookRepo(impl: WorkbookRepository): IWorkbookRepository = impl
    @Provides @Singleton fun providesWorkbookDescRepo(impl: WorkbookDescriptorRepository): IWorkbookDescriptorRepository = impl
    @Provides @Singleton fun providesInstalledEntityRepo(impl: InstalledEntityRepository): IInstalledEntityRepository = impl
//    @Provides @Singleton fun providesRegistrar(impl: AudioPluginRegistrar): IAudioPluginRegistrar = impl
//    @Provides @Singleton fun providesAppPreferencesRepo(impl: AppPreferencesRepository): IAppPreferencesRepository = impl
    @Provides @Singleton fun providesVersificationRepo(impl: VersificationRepository): IVersificationRepository = impl
//    @Provides @Singleton fun providesLocaleDataSource(impl: LocaleDataSource): ILocaleDataSource = impl
    @Provides @Singleton fun providesLanguageDataSource(impl: LanguageDataSource): ILanguageDataSource = impl
}

@Component
interface ZipEntryTreeBuilderModule {
    @Provides
    fun providesZipEntryTreeBuilder(impl: ZipEntryTreeBuilder): IZipEntryTreeBuilder = impl
}

@Component
interface MetadataModule {
//    @Provides
//    fun providesAppInfo(): IAppInfo = AppInfo()
}

@Component
interface AuthModule {
//    @Provides
//    fun providesWacsAuth(): AuthProvider = WacsIdAuthority()
}
