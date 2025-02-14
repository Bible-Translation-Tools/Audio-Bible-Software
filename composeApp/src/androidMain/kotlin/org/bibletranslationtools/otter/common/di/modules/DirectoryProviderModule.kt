package org.bibletranslationtools.otter.common.di.modules

import android.content.Context
import dagger.Module
import dagger.Provides
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.AndroidDirectoryProvider
import javax.inject.Singleton

@Module(includes = [
    AppContextModule::class,

])
class DirectoryProviderModule {
    @Provides
    @Singleton
    fun directoryProvider(context: Context): IDirectoryProvider {
        return AndroidDirectoryProvider(context)
    }
}