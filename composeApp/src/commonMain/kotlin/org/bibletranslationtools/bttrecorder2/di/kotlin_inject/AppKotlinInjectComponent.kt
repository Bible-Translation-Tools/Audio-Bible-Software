package org.bibletranslationtools.bttrecorder2.di.kotlin_inject

import me.tatarka.inject.annotations.Component
//import org.wycliffeassociates.otter.common.persistence.repositories.IAppPreferencesRepository
//import org.wycliffeassociates.otter.jvm.workbookapp.persistence.database.AppDatabase
import javax.inject.Singleton

@Component
@Singleton
abstract class AppKotlinInjectComponent :
    AudioModule,
    AppDatabaseModule,
    AppPreferencesModule,
    DirectoryProviderModule,
    AppRepositoriesModule,
    ZipEntryTreeBuilderModule,
    MetadataModule,
    AuthModule {

    // Abstract properties that expose dependencies for testing
//    abstract val appPreferencesRepository: IAppPreferencesRepository
//    abstract val appDatabase: AppDatabase

    // Add more as needed for testing
}
