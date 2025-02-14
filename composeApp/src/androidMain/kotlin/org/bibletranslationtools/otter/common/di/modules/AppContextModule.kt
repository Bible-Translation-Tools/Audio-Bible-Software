package org.bibletranslationtools.otter.common.di.modules

import android.app.Application
import android.content.Context
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

@Module
abstract class AppContextModule {  // to allow abstract method make module abstract
    @Singleton
    @Binds   // @Binds, binds the Application instance to Context
    abstract fun context(appInstance:Application): Context //just return the super-type you need

}