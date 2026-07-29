package org.bibletranslationtools.shared.di.koin

import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.domain.project.GlSourceCatalog
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.initialization.InitializeLanguages
import org.bibletranslationtools.otter.common.initialization.InitializeUlb
import org.bibletranslationtools.otter.common.initialization.InitializeVersification
import org.bibletranslationtools.otter.common.persistence.database.IAppDatabase
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The Koin graph in this module is wired by hand, so a dependency that is declared but never
 * bound compiles cleanly and only fails when the app starts — the same failure mode as the
 * `@Inject lateinit var` fields that Koin's constructor DSL silently skips (see the
 * `.apply { … = get() }` workarounds in ImplicitModules.kt).
 *
 * These resolve the consumers of [IBundledContentSource] out of [sharedCommonModules] so that
 * a missing or misplaced binding fails here instead of at first launch.
 *
 * Only the two genuinely platform-provided ports are stubbed ([IDirectoryProvider],
 * [IAppDatabase]); everything else must come from the shared modules under test.
 */
class BundledContentWiringTest : KoinTest {

    private val platformStubs = module {
        single<IDirectoryProvider> { mockk(relaxed = true) }
        single<IAppDatabase> { mockk(relaxed = true) }
    }

    private fun start() = startKoin { modules(sharedCommonModules + platformStubs) }.koin

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun `the bundled content source is bound`() {
        assertNotNull(start().get<IBundledContentSource>())
    }

    @Test
    fun `the gl source catalog is bound`() {
        assertNotNull(start().get<GlSourceCatalog>())
    }

    /**
     * The catalog parses its manifests in `by lazy` properties, so it only caches if every
     * consumer shares one instance — it must be a `single`, not a `factory`. As a companion
     * object it was implicitly process-wide.
     */
    @Test
    fun `the gl source catalog is a singleton`() {
        val koin = start()
        assertSame(koin.get<GlSourceCatalog>(), koin.get<GlSourceCatalog>())
    }

    @Test
    fun `the bundled content source is a singleton`() {
        val koin = start()
        assertSame(koin.get<IBundledContentSource>(), koin.get<IBundledContentSource>())
    }

    @Test
    fun `ImportProjectUseCase resolves with its bundled content dependencies`() {
        assertNotNull(start().get<ImportProjectUseCase>())
    }

    @Test
    fun `the initializers that read bundled content resolve`() {
        val koin = start()
        assertNotNull(koin.get<InitializeUlb>())
        assertNotNull(koin.get<InitializeLanguages>())
        assertNotNull(koin.get<InitializeVersification>())
    }
}
