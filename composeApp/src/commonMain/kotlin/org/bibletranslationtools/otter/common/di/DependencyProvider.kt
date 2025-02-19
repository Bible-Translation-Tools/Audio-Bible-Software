package org.bibletranslationtools.otter.common.di

import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SplashScreenViewModel

interface DependencyProvider {
    fun inject(splashScreenViewModel: SplashScreenViewModel)
}