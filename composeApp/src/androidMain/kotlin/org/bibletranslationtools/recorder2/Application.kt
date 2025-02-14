package org.bibletranslationtools.recorder2

import android.app.Application
import org.bibletranslationtools.otter.common.di.AppDependencyGraph
import org.bibletranslationtools.otter.common.di.DaggerAppDependencyGraph

class Application: Application() {
     val appComponent: AppDependencyGraph by lazy {
         DaggerAppDependencyGraph
             .builder()
             .application(this)
             .build()
     }
}