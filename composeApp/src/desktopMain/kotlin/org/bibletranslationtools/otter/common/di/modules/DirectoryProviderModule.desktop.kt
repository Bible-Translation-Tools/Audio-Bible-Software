package org.bibletranslationtools.otter.common.di.modules

import dagger.Module
import dagger.Provides
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import javax.inject.Singleton

@Module()
class DirectoryProviderModule {
    @Provides
    @Singleton
    fun directoryProvider(): IDirectoryProvider {
        return DesktopDirectoryProvider("BTT-Recorder")
    }
}