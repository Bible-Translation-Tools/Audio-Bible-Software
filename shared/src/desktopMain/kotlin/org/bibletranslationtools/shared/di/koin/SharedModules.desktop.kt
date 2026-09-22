package org.bibletranslationtools.shared.di.koin

import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.device.AudioConfig
import org.bibletranslationtools.otter.common.device.AudioDeviceSelector
import org.bibletranslationtools.otter.common.device.AudioHardwareProvider
import org.bibletranslationtools.otter.common.device.AudioSink
import org.bibletranslationtools.otter.common.device.AudioSource
import org.bibletranslationtools.otter.common.device.JvmAudioDeviceSelector
import org.bibletranslationtools.otter.common.device.JvmAudioHardwareProvider
import org.bibletranslationtools.otter.common.device.JvmAudioSink
import org.bibletranslationtools.otter.common.device.JvmAudioSource
import org.bibletranslationtools.otter.common.persistence.database.AppDatabase
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.DATABASE_FILE_NAME
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.DatabaseDriverFactory
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.JdbcDatabaseDriverFactory
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightDatabaseProvider
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.migratePathsForSandboxedMac
import org.koin.dsl.module
import java.io.File

// Generic (given an IDirectoryProvider, which each app supplies with its own appName).
val appDatabaseModule = module {
    // Unused by any repository now that DaoProvider (below) is the seam they resolve; kept in the
    // graph because it's harmless and nothing currently un-registers it. Phase 5 removes it once the
    // Android DI switch lands and jOOQ is deleted (Phase 6).
    single<IAppDatabase> {
        val directoryProvider = get<IDirectoryProvider>()
        AppDatabase(
            directoryProvider.databaseDirectory.resolve(File(DATABASE_FILE_NAME)),
            directoryProvider
        )
    }

    single<DatabaseDriverFactory> { JdbcDatabaseDriverFactory() }
    single<DaoProvider> {
        // ── Active persistence backend: SQLDelight ────────────────────────────────────────────────
        SqlDelightDatabaseProvider(
            driverFactory = get(),
            directoryProvider = get(),
            onOpened = ::migratePathsForSandboxedMac,   // self-guards on orature.isPkgMac
        ).provide()

        // ── To switch to the legacy jOOQ backend instead ─────────────────────────────────────────
        // Comment the block above and uncomment the line below. jOOQ is retained for the init benchmark
        // and as the characterization oracle. CAVEAT: JooqDaoProvider only implements the DAO surface; the
        // three repositories rewritten onto SQLDelight-only queries in Phase 4 (Collection/Resource/
        // ResourceContainer) would throw UnsupportedOperationException for project derivation until their
        // pre-Phase-4 jOOQ implementations are restored from git history.
        // JooqDaoProvider(get<IAppDatabase>())
    }
}

// Desktop audio hardware bridges (identical for every app).
val jvmAudioModule = module {
    single<AudioDeviceSelector> { JvmAudioDeviceSelector() }
    single<AudioHardwareProvider> { JvmAudioHardwareProvider(get()) }
    // A placeholder until AudioSystemConfig routes a real device in; it is never opened, but it is
    // built to the same buffer so nothing depends on which one it got.
    single<AudioSink> { JvmAudioSink(get<AudioConfig>().outputBufferMillis) { null } }
    single<AudioSource> { JvmAudioSource { null } }
}

/** Desktop platform half of the shared Koin graph. Compose with [sharedCommonModules]
 *  plus the app's own directory-provider + ViewModel modules in startKoin. */
val sharedDesktopModules = listOf(appDatabaseModule, jvmAudioModule)
