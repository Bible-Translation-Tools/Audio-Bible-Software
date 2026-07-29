package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.collections.DeleteProject
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * A resource version the user can pick in wizard step 4. Orature's JVM app models this as a
 * `org.wycliffeassociates.otter.jvm.controls.model.ResourceVersion` (a JavaFX control-layer
 * type that is not part of the ported backend), so we represent it here as a small Orature
 * data class built from a [org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata]
 * — its `slug` is the metadata `identifier` (passed to `createAllBooks` as the resourceId).
 */
data class OratureResourceVersion(
    val slug: String,
    val title: String
)

/**
 * Identifies the project group just created (or matched, when the user re-picks an existing
 * combination). Emitted on [OratureProjectWizardViewModel.projectCreated] so the home screen
 * can reselect that group in the projects pane — the four fields mirror [OratureProjectGroupKey].
 */
data class OratureCreatedProject(
    val sourceLanguageSlug: String,
    val targetLanguageSlug: String,
    val mode: ProjectMode,
    val resourceSlug: String?
)

/** The four wizard steps, docked in the home center pane in place of the book table. */
enum class WizardStep {
    SELECT_TYPE,
    SELECT_SOURCE_LANGUAGE,
    SELECT_TARGET_LANGUAGE,
    SELECT_VERSION
}

/**
 * Immutable snapshot of the wizard, exposed as a StateFlow. Mirrors the JavaFX
 * ProjectWizardViewModel's observable properties: current step, selected mode, the
 * filtered+sorted source/target language lists, resource versions, the two search queries,
 * and the loading flag.
 */
data class WizardUiState(
    val step: WizardStep = WizardStep.SELECT_TYPE,
    val mode: ProjectMode? = null,
    val selectedSourceLanguage: Language? = null,
    val selectedTargetLanguage: Language? = null,
    val sourceLanguages: List<Language> = emptyList(),
    val targetLanguages: List<Language> = emptyList(),
    val resourceVersions: List<OratureResourceVersion> = emptyList(),
    val sourceLanguageSearchQuery: String = "",
    val targetLanguageSearchQuery: String = "",
    val isLoading: Boolean = false
) {
    /** Source languages filtered+sorted by [sourceLanguageSearchQuery] (VM's setupLanguageSearchListener). */
    val visibleSourceLanguages: List<Language>
        get() = filterAndSortLanguages(sourceLanguages, sourceLanguageSearchQuery)

    /** Target languages filtered+sorted by [targetLanguageSearchQuery]. */
    val visibleTargetLanguages: List<Language>
        get() = filterAndSortLanguages(targetLanguages, targetLanguageSearchQuery)
}

/**
 * Filters and sorts a language list exactly like the JavaFX VM's `setupLanguageSearchListener`:
 * empty query -> all, sorted by slug; otherwise keep languages whose slug/name/anglicizedName
 * contains the query (case-insensitive), sorted so exact slug/name/anglicized matches surface first.
 */
internal fun filterAndSortLanguages(languages: List<Language>, query: String): List<Language> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return languages.sortedBy { it.slug }
    }
    val lower = trimmed.lowercase()
    return languages
        .filter {
            it.slug.contains(trimmed, ignoreCase = true) ||
                it.name.contains(trimmed, ignoreCase = true) ||
                it.anglicizedName.contains(trimmed, ignoreCase = true)
        }
        .sortedWith(
            compareByDescending<Language> { it.slug == lower }
                .thenByDescending { it.name.lowercase() == lower }
                .thenByDescending { it.anglicizedName.lowercase() == lower }
        )
}

/**
 * Orature's project-creation wizard, ported from the JVM
 * `org.wycliffeassociates.otter.jvm.workbookapp.ui.viewmodel.ProjectWizardViewModel` over the
 * SAME shared backend. Reproduces the four-step flow, the language search behaviour, the
 * quick-create paths, and the create logic (bookmark existing / sideload source / createAllBooks).
 *
 * DB/IO work runs on [Dispatchers.IO]; the state is only mutated from the main scope.
 */
class OratureProjectWizardViewModel(
    private val onComplete: () -> Unit = {}
) : ViewModel(), KoinComponent {

    private val creationUseCase: CreateProject by inject()
    @Suppress("unused") // kept to mirror the JVM VM's injected graph; used by the delete-queue guard
    private val deleteProjectUseCase: DeleteProject by inject()
    private val languageRepo: ILanguageRepository by inject()
    private val collectionRepo: ICollectionRepository by inject()
    private val resourceMetadataRepo: IResourceMetadataRepository by inject()
    private val workbookDescriptorRepo: IWorkbookDescriptorRepository by inject()
    private val importer: ImportProjectUseCase by inject()
    private val projectDeletion: OratureProjectDeletion by inject()

    private val _uiState = MutableStateFlow(WizardUiState())
    val uiState: StateFlow<WizardUiState> = _uiState.asStateFlow()

    // One-shot signal emitted when a project is created (or an existing one matched),
    // carrying the group key so the home screen can swap back to the book table AND
    // reselect that group — mirroring the JVM app's bookMarkedProjectGroupProperty, which
    // HomePageViewModel2.updateBookList honors as `bookmark ?: mostRecent`. `onComplete`
    // still fires (used by the wizard's unit tests as a "create finished" hook).
    private val _projectCreated = MutableSharedFlow<OratureCreatedProject>(extraBufferCapacity = 1)
    val projectCreated: SharedFlow<OratureCreatedProject> = _projectCreated.asSharedFlow()

    // ---- Type selection (step 1) --------------------------------------------------------

    /** Selecting a project type sets the mode and advances to source-language selection. */
    fun onModeSelected(mode: ProjectMode) {
        _uiState.value = _uiState.value.copy(mode = mode, step = WizardStep.SELECT_SOURCE_LANGUAGE)
        loadSourceLanguages()
    }

    // ---- Search (steps 2 & 3) -----------------------------------------------------------

    fun onSourceLanguageSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(sourceLanguageSearchQuery = query)
    }

    fun onTargetLanguageSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(targetLanguageSearchQuery = query)
    }

    // ---- Loading languages --------------------------------------------------------------

    /** Source languages = root-source RC languages ∪ available gateway sources (VM.loadSourceLanguages). */
    fun loadSourceLanguages() {
        launchLogged {
            val languages = withContext(Dispatchers.IO) {
                // Union of every "available source" signal, each fetched independently so
                // one failing path can't wipe the others (and errors are logged, not
                // swallowed). The JVM VM used getRootSources ∪ getAvailableGatewaySources;
                // we also include getAllSources()'s languages (the imported source RCs, e.g.
                // en_ulb) so already-imported sources always surface even if they aren't
                // gateway. getAvailableGatewaySources now returns the bundled gateway
                // languages (its listEmbeddedSourceLanguages reads the build-generated
                // manifest via Res, not a classpath lookup — fixed alongside the recorder).
                val result = linkedSetOf<Language>()
                runCatching {
                    resourceMetadataRepo.getAllSources().await().map { it.language }
                }.onFailure { System.err.println("[orature-wizard] getAllSources failed: $it") }
                    .getOrDefault(emptyList()).let(result::addAll)
                runCatching {
                    collectionRepo.getRootSources().await()
                        .mapNotNull { it.resourceContainer }.map { it.language }
                }.onFailure { System.err.println("[orature-wizard] getRootSources failed: $it") }
                    .getOrDefault(emptyList()).let(result::addAll)
                runCatching {
                    languageRepo.getAvailableGatewaySources().await()
                }.onFailure { System.err.println("[orature-wizard] getAvailableGatewaySources failed: $it") }
                    .getOrDefault(emptyList()).let(result::addAll)
                result.toList()
            }
            System.err.println("[orature-wizard] source languages loaded: ${languages.size}")
            _uiState.value = _uiState.value.copy(sourceLanguages = languages)
        }
    }

    /** Target languages = all languages (VM.loadTargetLanguages). */
    private fun loadTargetLanguages() {
        launchLogged {
            try {
                val languages = withContext(Dispatchers.IO) { languageRepo.getAll().await() }
                _uiState.value = _uiState.value.copy(targetLanguages = languages)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading target languages", e)
                _uiState.value = _uiState.value.copy(targetLanguages = emptyList())
            }
        }
    }

    // ---- Language selection (steps 2 & 3) ----------------------------------------------

    /**
     * Ported from VM.onLanguageSelected. Computes the resource versions for [language] (only
     * when no source is chosen yet), then:
     * - 1 version + NARRATION (no source) -> quick-create narration (source == target == language),
     * - 1 version + source already chosen -> quick-create (source, language),
     * - no source yet -> set source and (unless NARRATION) load target languages,
     * - else -> set target.
     */
    fun onLanguageSelected(language: Language) {
        val state = _uiState.value
        val projectMode = state.mode ?: return
        val sourceLanguage = state.selectedSourceLanguage

        _uiState.value = state.copy(isLoading = true)

        launchLogged {
            try {
                val versions = if (sourceLanguage == null) {
                    getResourceVersions(language)
                } else {
                    state.resourceVersions
                }

                val ignoreVersionSelect = versions.size == 1
                val quickCreateNarration = ignoreVersionSelect && projectMode == ProjectMode.NARRATION
                val quickCreateProject = ignoreVersionSelect && sourceLanguage != null

                when {
                    quickCreateNarration -> {
                        createProject(language, language, resourceVersion = null)
                    }

                    quickCreateProject -> {
                        createProject(sourceLanguage, language, resourceVersion = null)
                    }

                    sourceLanguage == null -> {
                        // Advance to target-language step. NARRATION forces target = source.
                        val next = if (projectMode == ProjectMode.NARRATION) {
                            _uiState.value.copy(
                                isLoading = false,
                                selectedSourceLanguage = language,
                                targetLanguages = listOf(language),
                                step = WizardStep.SELECT_TARGET_LANGUAGE
                            )
                        } else {
                            _uiState.value.copy(
                                isLoading = false,
                                selectedSourceLanguage = language,
                                step = WizardStep.SELECT_TARGET_LANGUAGE
                            )
                        }
                        _uiState.value = next
                        if (projectMode != ProjectMode.NARRATION) {
                            loadTargetLanguages()
                        }
                    }

                    else -> {
                        // Source chosen, multiple versions -> pick target, then advance to version step.
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            selectedTargetLanguage = language,
                            step = WizardStep.SELECT_VERSION
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("handling the wizard language selection", e)
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /** Ported from VM.onResourceVersionSelected -> createProject with the chosen version. */
    fun onResourceVersionSelected(version: OratureResourceVersion) {
        val state = _uiState.value
        val source = state.selectedSourceLanguage ?: return
        val target = state.selectedTargetLanguage ?: return
        _uiState.value = state.copy(isLoading = true)
        launchLogged {
            try {
                createProject(source, target, version)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("creating the project for the selected resource version", e)
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * Ported from VM.getResourceVersions: sideload source for [language] if no metadata exists,
     * then collect the language's source metadata as [OratureResourceVersion]s and stash them
     * in state (so the next-step version table and the multi-version branch can read them).
     */
    private suspend fun getResourceVersions(language: Language): List<OratureResourceVersion> {
        return withContext(Dispatchers.IO) {
            val exists = resourceMetadataRepo.exists { it.language == language }.await()
            if (!exists) {
                importer.sideloadSource(language).await()
            }
            val versions = resourceMetadataRepo.getAllSources().await()
                .filter { it.language == language }
                .map { OratureResourceVersion(it.identifier, it.title) }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(resourceVersions = versions)
            }
            versions
        }
    }

    // ---- Create -------------------------------------------------------------------------

    /**
     * Ported from VM.createProject: if the workbook already exists, this is a no-op create
     * (the JVM app bookmarks the group to reopen); otherwise sideload the source if missing,
     * then createAllBooks(source, target, mode, versionSlug). On success reset + onComplete.
     */
    private suspend fun createProject(
        sourceLanguage: Language,
        targetLanguage: Language,
        resourceVersion: OratureResourceVersion?
    ) {
        val mode = _uiState.value.mode ?: return
        _uiState.value = _uiState.value.copy(isLoading = true)

        // Wait for any in-flight project-group delete to finish first, so we don't derive against
        // rows a delete is concurrently removing (JVM: waitForProjectDeletionFinishes).
        projectDeletion.awaitClear()

        val existing = findExistingWorkbook(resourceVersion, sourceLanguage, targetLanguage)
        if (existing != null) {
            // Already created (JVM: findExistingWorkBook != null): don't build a duplicate;
            // bookmark the existing group so the home screen reselects it. Derive the
            // resource slug exactly like OratureHomeViewModel.groupKey so the keys match.
            val existingResourceSlug = existing.sourceCollection.resourceContainer?.identifier
                ?: existing.sourceCollection.slug
            val bookmark = OratureCreatedProject(
                sourceLanguageSlug = sourceLanguage.slug,
                targetLanguageSlug = targetLanguage.slug,
                mode = mode,
                resourceSlug = existingResourceSlug
            )
            reset()
            withContext(Dispatchers.Main) { signalComplete(bookmark) }
            return
        }

        withContext(Dispatchers.IO) {
            val sourceExists = collectionRepo.getRootSources().await()
                .any { it.resourceContainer?.language == sourceLanguage }
            if (!sourceExists) {
                importer.sideloadSource(sourceLanguage).await()
            }
            creationUseCase.createAllBooks(
                sourceLanguage,
                targetLanguage,
                mode,
                resourceVersion?.slug
            ).await()
        }

        // resourceVersion.slug is the source metadata identifier, which the home VM uses as
        // the group key's resourceSlug. When null (single-version fast-track), the home VM
        // matches on source+target+mode alone (picking the most-recent such group).
        val bookmark = OratureCreatedProject(
            sourceLanguageSlug = sourceLanguage.slug,
            targetLanguageSlug = targetLanguage.slug,
            mode = mode,
            resourceSlug = resourceVersion?.slug
        )
        reset()
        withContext(Dispatchers.Main) { signalComplete(bookmark) }
    }

    /** Fire the create-finished hook and tell the home screen which group to reselect. */
    private fun signalComplete(bookmark: OratureCreatedProject) {
        onComplete()
        _projectCreated.tryEmit(bookmark)
    }

    /** Ported from VM.findExistingWorkBook: match source+target language and (if given) resource version. */
    private suspend fun findExistingWorkbook(
        resourceVersion: OratureResourceVersion?,
        sourceLanguage: Language,
        targetLanguage: Language
    ) = withContext(Dispatchers.IO) {
        workbookDescriptorRepo.getAll().await().firstOrNull { wb ->
            val sourceVersionMatches = resourceVersion?.slug?.let {
                wb.sourceCollection.resourceContainer?.identifier == it
            } != false

            wb.sourceLanguage == sourceLanguage &&
                wb.targetLanguage == targetLanguage &&
                sourceVersionMatches
        }
    }

    // ---- Navigation / lifecycle ---------------------------------------------------------

    /**
     * Back navigation, mirroring ProjectWizardSection's per-step back button:
     * - step 2 -> clear mode -> step 1,
     * - step 3 -> clear source -> step 2,
     * - step 4 -> clear target -> step 3,
     * - step 1 -> returns false so the caller cancels the wizard.
     *
     * @return true if it moved back a step, false if the wizard should be cancelled.
     */
    fun onBack(): Boolean {
        val state = _uiState.value
        return when (state.step) {
            WizardStep.SELECT_TYPE -> false
            WizardStep.SELECT_SOURCE_LANGUAGE -> {
                _uiState.value = state.copy(
                    mode = null,
                    sourceLanguageSearchQuery = "",
                    sourceLanguages = emptyList(),
                    step = WizardStep.SELECT_TYPE
                )
                true
            }
            WizardStep.SELECT_TARGET_LANGUAGE -> {
                _uiState.value = state.copy(
                    selectedSourceLanguage = null,
                    targetLanguageSearchQuery = "",
                    targetLanguages = emptyList(),
                    resourceVersions = emptyList(),
                    step = WizardStep.SELECT_SOURCE_LANGUAGE
                )
                true
            }
            WizardStep.SELECT_VERSION -> {
                _uiState.value = state.copy(
                    selectedTargetLanguage = null,
                    step = WizardStep.SELECT_TARGET_LANGUAGE
                )
                true
            }
        }
    }

    /** Reset all wizard state (VM.reset). Called on dock, on cancel, and after a successful create. */
    fun reset() {
        _uiState.value = WizardUiState()
    }
}
