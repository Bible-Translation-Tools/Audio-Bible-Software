/**
 * Copyright (C) 2020-2024 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.di

//import android.app.Application
//import dagger.BindsInstance
//import dagger.Component
//import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SplashScreenViewModel
//import org.bibletranslationtools.otter.common.di.modules.AppContextModule
//import org.bibletranslationtools.otter.common.di.modules.AppDatabaseModule
//import org.bibletranslationtools.otter.common.di.modules.AppRepositoriesModule
//import org.bibletranslationtools.otter.common.di.modules.DirectoryProviderModule
//import org.bibletranslationtools.otter.common.di.modules.ZipEntryTreeBuilderModule
//import org.bibletranslationtools.bttrecorder2.MainActivity
//import javax.inject.Singleton
//
//@Component(
//    modules = [
//        AppContextModule::class,
//        DirectoryProviderModule::class,
//        AppRepositoriesModule::class,
//        AppDatabaseModule::class,
//        ZipEntryTreeBuilderModule::class
//    ]
//)
//@Singleton
//interface AppDependencyGraph: DependencyProvider {
//    @Component.Builder
//    interface Builder {
//        @BindsInstance
//        fun application(application: Application): Builder
//
//        fun build(): AppDependencyGraph
//    }
//
//    fun inject(activity: MainActivity)
//    override fun inject(viewModel: SplashScreenViewModel)
//}
