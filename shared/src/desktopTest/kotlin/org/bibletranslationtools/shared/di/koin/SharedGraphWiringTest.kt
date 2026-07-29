package org.bibletranslationtools.shared.di.koin

import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.domain.audio.AudioExporter
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.project.exporter.AudioProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.BackupProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.SourceProjectExporter
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
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Koin graph in this module is wired by hand, so a dependency that is declared but never
 * bound compiles cleanly and only fails when the app starts — the same failure mode as the
 * `@Inject lateinit var` fields that Koin's constructor DSL silently skips (see the
 * `.apply { … = get() }` workarounds in ImplicitModules.kt).
 *
 * These resolve definitions out of [sharedCommonModules] so that a missing or misplaced
 * binding fails here instead of at first launch.
 *
 * Only the two genuinely platform-provided ports are stubbed ([IDirectoryProvider],
 * [IAppDatabase]); everything else must come from the shared modules under test.
 */
class SharedGraphWiringTest : KoinTest {

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

    /**
     * These six used to declare a collaborator as a Dagger-style `@Inject lateinit var`, which
     * no DI framework in this build ever populated — there is no Dagger compiler, so Koin's
     * constructor DSL was the only wiring and it cannot see fields. Each therefore needed a
     * hand-written `.apply { field = get() }` in the module, and a forgotten one surfaced as an
     * `UninitializedPropertyAccessException` the first time the collaborator was touched
     * (importing a project, exporting audio, listing project progress).
     *
     * The dependencies are constructor parameters now, so the compiler enforces them and the
     * `.apply` blocks are gone. Resolving each here proves the corresponding
     * `factoryOf`/`singleOf` bindings can actually satisfy the widened constructors.
     */
    @Test
    fun `formerly field-injected classes resolve with their collaborators`() {
        val koin = start()
        assertNotNull(koin.get<CreateProject>())
        assertNotNull(koin.get<AudioExporter>())
        assertNotNull(koin.get<AudioProjectExporter>())
        assertNotNull(koin.get<BackupProjectExporter>())
        assertNotNull(koin.get<IWorkbookDescriptorRepository>())
    }

    /**
     * KNOWN GAP, pre-existing and not yet fixed — this test documents it rather than asserting
     * the behaviour we want.
     *
     * [SourceProjectExporter] cannot be resolved. It needs `ScriptureBurritoUtils`, whose
     * constructor takes `AuthProvider` and `IAppInfo`; both are interfaces with no
     * implementation anywhere in the repo, and both Koin bindings are commented out in
     * KoinModules.kt (`authModule`, `metadataModule` — the old JavaFX `WacsIdAuthority` and
     * `AppInfo` were never ported).
     *
     * This is not dormant: both apps declare `private val sourceExporter: SourceProjectExporter
     * by inject()` in their export ViewModels. `by inject()` resolves lazily, so it throws the
     * first time a user chooses the source / Scripture Burrito export type.
     *
     * When those bindings are supplied, this test will start failing — at which point move
     * `SourceProjectExporter` into the positive assertion above and delete this.
     */
    @Test
    fun `source project exporter is unresolvable while AuthProvider and IAppInfo are unbound`() {
        val error = assertFails { start().get<SourceProjectExporter>() }
        val chain = generateSequence(error as Throwable?) { it.cause }.joinToString { it.message ?: "" }
        assertTrue(
            "AuthProvider" in chain,
            "expected the missing AuthProvider binding to be the cause, but got: $chain"
        )
    }
}
