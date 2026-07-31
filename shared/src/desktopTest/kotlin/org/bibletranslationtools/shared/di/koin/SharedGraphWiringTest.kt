package org.bibletranslationtools.shared.di.koin

import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.api.persistence.IFileIOFactory
import org.bibletranslationtools.otter.common.api.persistence.IProjectDirectories
import org.bibletranslationtools.otter.common.api.persistence.IResourceContainerDirectories
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.domain.audio.AudioExporter
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.narration.LoadChapterSourceText
import org.bibletranslationtools.otter.common.domain.content.SaveAudioAsNewTake
import org.bibletranslationtools.otter.common.domain.project.exporter.AudioProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.BackupProjectExporter
import org.bibletranslationtools.otter.common.domain.project.exporter.resourcecontainer.SourceProjectExporter
import org.bibletranslationtools.otter.common.domain.project.GlSourceCatalog
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.InitializeProjectFiles
import org.bibletranslationtools.otter.common.domain.project.OpenWorkbook
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

    /**
     * Each narrow slice of the former 27-member directory god interface must be resolvable on
     * its own, because that is what narrowed constructors now ask Koin for. A missing binding
     * here would not fail at startup — the ViewModels reach these through lazy `by inject()`,
     * so it would surface as a throw on the first import/export the user attempts.
     */
    @Test
    fun `every narrow directory port is bound`() {
        val koin = start()
        assertNotNull(koin.get<IAppDirectories>())
        assertNotNull(koin.get<ITempFileProvider>())
        assertNotNull(koin.get<IProjectDirectories>())
        assertNotNull(koin.get<IResourceContainerDirectories>())
        assertNotNull(koin.get<IFileIOFactory>())
    }

    /**
     * Splitting the interface must not split the object. The narrow ports delegate to the single
     * [IDirectoryProvider] rather than constructing anything, so a caller holding
     * [ITempFileProvider] and a caller holding [IAppDirectories] are talking to the same temp
     * directory — binding them to separate instances would silently give two views of the disk.
     */
    @Test
    fun `the narrow directory ports all resolve to the one provider instance`() {
        val koin = start()
        val provider = koin.get<IDirectoryProvider>()
        assertSame(provider, koin.get<IAppDirectories>())
        assertSame(provider, koin.get<ITempFileProvider>())
        assertSame(provider, koin.get<IProjectDirectories>())
        assertSame(provider, koin.get<IResourceContainerDirectories>())
        assertSame(provider, koin.get<IFileIOFactory>())
    }

    @Test
    fun `the gl source catalog is bound`() {
        assertNotNull(start().get<GlSourceCatalog>())
    }

    /**
     * Orature's [org.bibletranslationtools.orature.services.OratureWorkbookDataStore] takes this by
     * constructor, and it is a Koin `single` — so an unbound use case fails when the graph builds
     * the data store, not when the project files are written. Adding it as a constructor parameter
     * broke four narration ViewModel tests with "Could not create instance for Singleton
     * OratureWorkbookDataStore" and nothing about the actual missing binding.
     */
    @Test
    fun `initialize project files is bound`() {
        assertNotNull(start().get<InitializeProjectFiles>())
    }

    /**
     * Both apps' ViewModels reach these two through Koin — the recorder's PlaybackViewModel by
     * constructor, Orature's chapter-review ViewModel by lazy `by inject()`. A missing binding on
     * the lazy path would not surface until the user saved a take or their verse markers.
     */
    @Test
    fun `the take-save and marker-write use cases are bound`() {
        val koin = start()
        assertNotNull(koin.get<SaveAudioAsNewTake>())
        assertNotNull(koin.get<WriteTakeMarkers>())
    }

    /**
     * OratureNarrationViewModel injects both lazily, so an unbound one would not fail at startup —
     * it would fail when the narration screen opened. Extracting these out of the ViewModel is
     * only safe if the graph can actually supply them.
     */
    @Test
    fun `the workbook-open and source-text use cases are bound`() {
        val koin = start()
        assertNotNull(koin.get<OpenWorkbook>())
        assertNotNull(koin.get<LoadChapterSourceText>())
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
     * implementation anywhere in the repo, and neither is bound in KoinModules.kt — the old
     * JavaFX `WacsIdAuthority` and `AppInfo` were never ported. See the note above
     * `sharedCommonModules` in KoinModules.kt.
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
