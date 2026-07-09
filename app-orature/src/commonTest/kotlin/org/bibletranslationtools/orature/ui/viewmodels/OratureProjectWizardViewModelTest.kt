package org.bibletranslationtools.orature.ui.viewmodels

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.reactivex.Completable
import io.reactivex.Single
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.collections.DeleteProject
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OratureProjectWizardViewModelTest : KoinTest {

    private val createProject: CreateProject = mockk(relaxed = true)
    private val deleteProject: DeleteProject = mockk(relaxed = true)
    private val languageRepo: ILanguageRepository = mockk(relaxed = true)
    private val collectionRepo: ICollectionRepository = mockk(relaxed = true)
    private val resourceMetadataRepo: IResourceMetadataRepository = mockk(relaxed = true)
    private val workbookDescriptorRepo: IWorkbookDescriptorRepository = mockk(relaxed = true)
    private val importer: ImportProjectUseCase = mockk(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        startKoin {
            modules(
                module {
                    single { createProject }
                    single { deleteProject }
                    single { languageRepo }
                    single { collectionRepo }
                    single { resourceMetadataRepo }
                    single { workbookDescriptorRepo }
                    single { importer }
                }
            )
        }
        // Sensible defaults; individual tests override as needed.
        every { collectionRepo.getRootSources() } returns Single.just(emptyList())
        every { languageRepo.getAvailableGatewaySources() } returns Single.just(emptyList())
        every { languageRepo.getAll() } returns Single.just(emptyList())
        every { workbookDescriptorRepo.getAll() } returns Single.just(emptyList())
        every { resourceMetadataRepo.getAllSources() } returns Single.just(emptyList())
        every { resourceMetadataRepo.exists(any<(ResourceMetadata) -> Boolean>()) } returns Single.just(true)
        every { importer.sideloadSource(any()) } returns Completable.complete()
        every { createProject.createAllBooks(any(), any(), any(), any()) } returns Completable.complete()
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun lang(slug: String, name: String, anglicized: String = name) = Language(
        slug = slug,
        name = name,
        anglicizedName = anglicized,
        direction = "ltr",
        isGateway = true,
        region = "US"
    )

    private fun metadata(identifier: String, title: String, language: Language) = ResourceMetadata(
        conformsTo = "rc0.2",
        creator = "WA",
        description = "Test",
        format = "audio/wav",
        identifier = identifier,
        issued = LocalDate.now(),
        language = language,
        modified = LocalDate.now(),
        publisher = "WA",
        subject = "Bible",
        type = ContainerType.Bundle,
        title = title,
        version = "1",
        license = "CC BY-SA 4.0",
        path = File("")
    )

    private fun collection(language: Language, resourceSlug: String) = Collection(
        sort = 1,
        slug = "gen",
        labelKey = "Genesis",
        titleKey = "Genesis",
        resourceContainer = metadata(resourceSlug, resourceSlug.uppercase(), language)
    )

    private fun descriptor(
        source: Language,
        target: Language,
        resourceSlug: String,
        mode: ProjectMode = ProjectMode.TRANSLATION
    ) = WorkbookDescriptor(
        id = 1,
        sourceCollection = collection(source, resourceSlug),
        targetCollection = collection(target, resourceSlug),
        mode = mode,
        progress = Single.just(0.0)
    )

    private fun newVm(onComplete: () -> Unit = {}) = OratureProjectWizardViewModel(onComplete)

    private suspend fun OratureProjectWizardViewModel.awaitState(
        predicate: (WizardUiState) -> Boolean
    ): WizardUiState = withTimeout(5000) { uiState.first(predicate) }

    /**
     * Runs a test that crosses Dispatchers.IO. Uses a REAL main dispatcher (not the virtual
     * test scheduler) so the VM's withContext(IO) work and its Main republishes actually run,
     * awaiting state transitions with real wall-clock time — mirroring the settings VM tests.
     */
    private fun runReal(block: suspend () -> Unit) {
        Dispatchers.resetMain()
        try {
            runBlocking { withTimeout(10_000) { block() } }
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    // ---- step state machine -------------------------------------------------------------

    @Test
    fun `selecting a type advances to source language step`() = runReal {
        val english = lang("eng", "English")
        every { collectionRepo.getRootSources() } returns Single.just(listOf(collection(english, "ulb")))

        val vm = newVm()
        vm.onModeSelected(ProjectMode.TRANSLATION)

        val state = vm.awaitState { it.step == WizardStep.SELECT_SOURCE_LANGUAGE }
        assertEquals(ProjectMode.TRANSLATION, state.mode)
    }

    @Test
    fun `back navigation clears the right selection at each step`() = runReal {
        val english = lang("eng", "English")
        val spanish = lang("spa", "Spanish")
        // Two versions -> forces the multi-step (no quick-create) path.
        every { collectionRepo.getRootSources() } returns Single.just(listOf(collection(english, "ulb")))
        every { resourceMetadataRepo.getAllSources() } returns Single.just(
            listOf(metadata("ulb", "ULB", english), metadata("udb", "UDB", english))
        )
        every { languageRepo.getAll() } returns Single.just(listOf(english, spanish))

        val vm = newVm()
        vm.onModeSelected(ProjectMode.TRANSLATION)
        vm.awaitState { it.step == WizardStep.SELECT_SOURCE_LANGUAGE }

        // Pick source -> advances to target with 2 versions stashed.
        vm.onLanguageSelected(english)
        vm.awaitState { it.step == WizardStep.SELECT_TARGET_LANGUAGE && it.selectedSourceLanguage == english }

        // Pick target -> advances to version step.
        vm.onLanguageSelected(spanish)
        vm.awaitState { it.step == WizardStep.SELECT_VERSION && it.selectedTargetLanguage == spanish }

        // Back from version clears target -> target step.
        assertTrue(vm.onBack())
        var s = vm.uiState.value
        assertEquals(WizardStep.SELECT_TARGET_LANGUAGE, s.step)
        assertNull(s.selectedTargetLanguage)

        // Back from target clears source -> source step.
        assertTrue(vm.onBack())
        s = vm.uiState.value
        assertEquals(WizardStep.SELECT_SOURCE_LANGUAGE, s.step)
        assertNull(s.selectedSourceLanguage)

        // Back from source clears mode -> type step.
        assertTrue(vm.onBack())
        s = vm.uiState.value
        assertEquals(WizardStep.SELECT_TYPE, s.step)
        assertNull(s.mode)

        // Back from type returns false (cancel).
        assertFalse(vm.onBack())
    }

    // ---- language search filter ---------------------------------------------------------

    @Test
    fun `language search filters by slug, name, and anglicized name`() {
        val languages = listOf(
            lang("eng", "English", "English"),
            lang("spa", "Español", "Spanish"),
            lang("fra", "Français", "French")
        )

        // slug match
        assertEquals(listOf("eng"), filterAndSortLanguages(languages, "eng").map { it.slug })
        // name (native) match
        assertEquals(listOf("spa"), filterAndSortLanguages(languages, "Español").map { it.slug })
        // anglicized match
        assertEquals(listOf("fra"), filterAndSortLanguages(languages, "French").map { it.slug })
        // empty query returns all, sorted by slug
        assertEquals(listOf("eng", "fra", "spa"), filterAndSortLanguages(languages, "").map { it.slug })
    }

    @Test
    fun `exact slug match sorts ahead of partial matches`() {
        val languages = listOf(
            lang("en-x-partial", "Partial", "Partial"),
            lang("en", "English", "English")
        )
        // Query "en" matches both; exact slug "en" surfaces first.
        assertEquals("en", filterAndSortLanguages(languages, "en").first().slug)
    }

    // ---- quick-create paths -------------------------------------------------------------

    @Test
    fun `single version narration quick-creates with source equal target`() = runReal {
        val english = lang("eng", "English")
        every { collectionRepo.getRootSources() } returns Single.just(listOf(collection(english, "ulb")))
        // Exactly one version for the selected language.
        every { resourceMetadataRepo.getAllSources() } returns Single.just(listOf(metadata("ulb", "ULB", english)))

        val srcSlot = slot<Language>()
        val tgtSlot = slot<Language>()
        val modeSlot = slot<ProjectMode>()
        val versionSlot = slot<String?>()
        every {
            createProject.createAllBooks(capture(srcSlot), capture(tgtSlot), capture(modeSlot), captureNullable(versionSlot))
        } returns Completable.complete()

        val done = CompletableDeferred<Unit>()
        val vm = newVm(onComplete = { done.complete(Unit) })
        vm.onModeSelected(ProjectMode.NARRATION)
        vm.awaitState { it.step == WizardStep.SELECT_SOURCE_LANGUAGE }

        vm.onLanguageSelected(english)
        done.await() // create completed -> onComplete invoked

        verify { createProject.createAllBooks(english, english, ProjectMode.NARRATION, null) }
        assertEquals(english, srcSlot.captured)
        assertEquals(english, tgtSlot.captured)
        assertNull(versionSlot.captured)
    }

    @Test
    fun `single version with source already chosen quick-creates`() = runReal {
        val english = lang("eng", "English")
        val spanish = lang("spa", "Spanish")
        every { collectionRepo.getRootSources() } returns Single.just(listOf(collection(english, "ulb")))
        // One version -> selecting the source quick-creates immediately (no target step needed
        // in the JVM flow only when source already set). Here we drive TRANSLATION: source pick
        // with a single version sets the source, then the target pick quick-creates.
        every { resourceMetadataRepo.getAllSources() } returns Single.just(listOf(metadata("ulb", "ULB", english)))
        every { languageRepo.getAll() } returns Single.just(listOf(english, spanish))

        val done = CompletableDeferred<Unit>()
        val vm = newVm(onComplete = { done.complete(Unit) })
        vm.onModeSelected(ProjectMode.TRANSLATION)
        vm.awaitState { it.step == WizardStep.SELECT_SOURCE_LANGUAGE }

        // First selection: no source yet, single version -> sets source, advances to target.
        vm.onLanguageSelected(english)
        vm.awaitState { it.step == WizardStep.SELECT_TARGET_LANGUAGE && it.selectedSourceLanguage == english }

        // Second selection: source set, single version -> quick-create (source=eng, target=spa).
        vm.onLanguageSelected(spanish)
        done.await()

        verify { createProject.createAllBooks(english, spanish, ProjectMode.TRANSLATION, null) }
    }

    @Test
    fun `multiple versions require version selection to create`() = runReal {
        val english = lang("eng", "English")
        val spanish = lang("spa", "Spanish")
        every { collectionRepo.getRootSources() } returns Single.just(listOf(collection(english, "ulb")))
        every { resourceMetadataRepo.getAllSources() } returns Single.just(
            listOf(metadata("ulb", "ULB", english), metadata("udb", "UDB", english))
        )
        every { languageRepo.getAll() } returns Single.just(listOf(english, spanish))

        val done = CompletableDeferred<Unit>()
        val vm = newVm(onComplete = { done.complete(Unit) })
        vm.onModeSelected(ProjectMode.TRANSLATION)
        vm.awaitState { it.step == WizardStep.SELECT_SOURCE_LANGUAGE }
        vm.onLanguageSelected(english)
        vm.awaitState { it.step == WizardStep.SELECT_TARGET_LANGUAGE }
        vm.onLanguageSelected(spanish)
        val versionState = vm.awaitState { it.step == WizardStep.SELECT_VERSION }

        // Not created yet.
        verify(exactly = 0) { createProject.createAllBooks(any(), any(), any(), any()) }
        assertEquals(2, versionState.resourceVersions.size)

        // Selecting a version creates with that version slug.
        vm.onResourceVersionSelected(versionState.resourceVersions.first { it.slug == "udb" })
        done.await()
        verify { createProject.createAllBooks(english, spanish, ProjectMode.TRANSLATION, "udb") }
    }

    // ---- createProject wiring & existing-workbook bookmark ------------------------------

    @Test
    fun `existing workbook is not recreated but still completes`() = runReal {
        val english = lang("eng", "English")
        every { collectionRepo.getRootSources() } returns Single.just(listOf(collection(english, "ulb")))
        every { resourceMetadataRepo.getAllSources() } returns Single.just(listOf(metadata("ulb", "ULB", english)))
        // A workbook for (eng, eng, ulb-derived) already exists -> bookmark path, no createAllBooks.
        every { workbookDescriptorRepo.getAll() } returns Single.just(
            listOf(descriptor(english, english, "ulb", ProjectMode.NARRATION))
        )

        val done = CompletableDeferred<Unit>()
        val vm = newVm(onComplete = { done.complete(Unit) })
        vm.onModeSelected(ProjectMode.NARRATION)
        vm.awaitState { it.step == WizardStep.SELECT_SOURCE_LANGUAGE }

        vm.onLanguageSelected(english)
        done.await()

        verify(exactly = 0) { createProject.createAllBooks(any(), any(), any(), any()) }
    }

    // ---- projectCreated bookmark (home reselects the created/matched group) -------------

    @Test
    fun `create emits a bookmark carrying the created group key`() = runReal {
        val english = lang("eng", "English")
        val spanish = lang("spa", "Spanish")
        every { collectionRepo.getRootSources() } returns Single.just(listOf(collection(english, "ulb")))
        every { resourceMetadataRepo.getAllSources() } returns Single.just(
            listOf(metadata("ulb", "ULB", english), metadata("udb", "UDB", english))
        )
        every { languageRepo.getAll() } returns Single.just(listOf(english, spanish))

        val vm = newVm()
        coroutineScope {
            // Subscribe before triggering: the async body subscribes when the first
            // awaitState below suspends this coroutine, long before the create emits.
            val bookmark = async { vm.projectCreated.first() }

            vm.onModeSelected(ProjectMode.TRANSLATION)
            vm.awaitState { it.step == WizardStep.SELECT_SOURCE_LANGUAGE }
            vm.onLanguageSelected(english)
            vm.awaitState { it.step == WizardStep.SELECT_TARGET_LANGUAGE }
            vm.onLanguageSelected(spanish)
            val versionState = vm.awaitState { it.step == WizardStep.SELECT_VERSION }
            vm.onResourceVersionSelected(versionState.resourceVersions.first { it.slug == "udb" })

            val created = withTimeout(5000) { bookmark.await() }
            assertEquals("eng", created.sourceLanguageSlug)
            assertEquals("spa", created.targetLanguageSlug)
            assertEquals(ProjectMode.TRANSLATION, created.mode)
            assertEquals("udb", created.resourceSlug)
        }
    }

    @Test
    fun `existing workbook emits a bookmark for the matched group`() = runReal {
        val english = lang("eng", "English")
        every { collectionRepo.getRootSources() } returns Single.just(listOf(collection(english, "ulb")))
        every { resourceMetadataRepo.getAllSources() } returns Single.just(listOf(metadata("ulb", "ULB", english)))
        every { workbookDescriptorRepo.getAll() } returns Single.just(
            listOf(descriptor(english, english, "ulb", ProjectMode.NARRATION))
        )

        val vm = newVm()
        coroutineScope {
            val bookmark = async { vm.projectCreated.first() }

            vm.onModeSelected(ProjectMode.NARRATION)
            vm.awaitState { it.step == WizardStep.SELECT_SOURCE_LANGUAGE }
            vm.onLanguageSelected(english)

            val created = withTimeout(5000) { bookmark.await() }
            assertEquals("eng", created.sourceLanguageSlug)
            assertEquals("eng", created.targetLanguageSlug)
            assertEquals(ProjectMode.NARRATION, created.mode)
            assertEquals("ulb", created.resourceSlug)
        }
        verify(exactly = 0) { createProject.createAllBooks(any(), any(), any(), any()) }
    }

    @Test
    fun `createProject sideloads source when metadata is missing`() = runReal {
        val english = lang("eng", "English")
        val spanish = lang("spa", "Spanish")
        // No root sources -> source metadata missing -> sideload before createAllBooks.
        every { collectionRepo.getRootSources() } returns Single.just(emptyList())
        every { resourceMetadataRepo.getAllSources() } returns Single.just(listOf(metadata("ulb", "ULB", english)))
        every { languageRepo.getAll() } returns Single.just(listOf(english, spanish))

        val done = CompletableDeferred<Unit>()
        val vm = newVm(onComplete = { done.complete(Unit) })
        vm.onModeSelected(ProjectMode.TRANSLATION)
        vm.awaitState { it.step == WizardStep.SELECT_SOURCE_LANGUAGE }
        vm.onLanguageSelected(english)
        vm.awaitState { it.step == WizardStep.SELECT_TARGET_LANGUAGE }
        vm.onLanguageSelected(spanish)
        done.await()

        verify { importer.sideloadSource(english) }
        verify { createProject.createAllBooks(english, spanish, ProjectMode.TRANSLATION, null) }
    }
}
