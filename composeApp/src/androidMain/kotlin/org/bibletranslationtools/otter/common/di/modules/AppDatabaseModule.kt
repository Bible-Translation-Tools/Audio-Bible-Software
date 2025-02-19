package org.bibletranslationtools.otter.common.di.modules

import android.content.Context
import dagger.Module
import dagger.Provides
import org.bibletranslationtools.otter.common.api.persistence.AppDatabase
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.database.AndroidAppDatabase
import java.io.File
import javax.inject.Singleton

@Module(includes = [
    AppContextModule::class,
])
class AppDatabaseModule {
    @Provides
    @Singleton
    fun providesAppDatabase(
        context: Context,
        directoryProvider: IDirectoryProvider
    ): AppDatabase {
        return AndroidAppDatabase(
            context,
            directoryProvider
                .databaseDirectory
                .resolve(File("tr.db")),
            directoryProvider
        )
    }
}
