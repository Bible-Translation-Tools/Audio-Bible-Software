package org.bibletranslationtools.orature.di

import org.bibletranslationtools.orature.ui.viewmodels.OratureHomeViewModel
import org.koin.dsl.module

/**
 * Orature's own ViewModels (NOT shared with the recorder). Composed in startKoin
 * alongside :shared's sharedCommonModules and the platform module + directory provider,
 * mirroring the recorder's recorderViewModelModule pattern.
 */
val oratureViewModelModule = module {
    single { OratureHomeViewModel() }
}
