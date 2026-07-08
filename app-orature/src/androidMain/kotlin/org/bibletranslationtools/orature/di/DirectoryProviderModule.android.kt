package org.bibletranslationtools.orature.di

import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.AndroidDirectoryProvider
import org.koin.dsl.module

// Android data dir is per-applicationId sandboxed; Context-backed provider.
val oratureDirectoryProviderModule = module {
    single<IDirectoryProvider> { AndroidDirectoryProvider(get()) }
}
