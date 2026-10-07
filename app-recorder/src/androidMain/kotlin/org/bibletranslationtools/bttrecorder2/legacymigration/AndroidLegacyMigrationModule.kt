package org.bibletranslationtools.bttrecorder2.legacymigration

import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.InputStream

/** The templates, from this app's assets (`assets/legacy-migration/`). */
class AndroidUlbTemplateFiles(private val context: Context) : IUlbTemplateFiles {
    override fun open(template: UlbTemplate): InputStream = context.assets.open(template.assetPath)
}

/**
 * The legacy migration on Android: [legacyMigrationModule] with the device's old BTT-Recorder data
 * and the shipped templates. Only the Android recorder's graph includes it.
 */
val androidLegacyMigrationModules = listOf(
    legacyMigrationModule,
    module {
        single<ILegacyProjectReader> { AndroidLegacyProjectReader(androidContext()) }
        single<IUlbTemplateFiles> { AndroidUlbTemplateFiles(androidContext()) }
    }
)
