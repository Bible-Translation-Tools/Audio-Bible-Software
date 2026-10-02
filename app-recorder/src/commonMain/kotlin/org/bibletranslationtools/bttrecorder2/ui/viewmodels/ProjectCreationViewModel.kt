package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import org.bibletranslationtools.otter.common.domain.resourcecontainer.DescribeSourceEditions
import org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceEditionSummary
import org.bibletranslationtools.otter.common.domain.resourcecontainer.EditionOrder
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.err_source_collection_not_found
import org.bibletranslationtools.shared.resources.import_failed
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

enum class WizardStep {
    SOURCE,
    /** Only when the chosen source has more than one edition installed. */
    EDITION,
    TARGET_LANGUAGE,
    BOOK
}

data class ProjectCreationUiState(
    val currentStep: WizardStep = WizardStep.SOURCE,
    val sources: List<ResourceMetadata> = emptyList(),
    // Gateway-language sources that ship bundled but aren't imported yet. Selecting one
    // sideloads (imports) it on demand, mirroring Orature's project wizard.
    val availableSources: List<Language> = emptyList(),
    val targetLanguages: List<Language> = emptyList(),
    val availableBooks: List<Collection> = emptyList(),
    /** The installed editions of the chosen source, newest first, for the edition step. */
    val editions: List<EditionOption> = emptyList(),
    val selectedSource: ResourceMetadata? = null,
    val selectedTarget: Language? = null,
    val selectedBook: Collection? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isCreated: Boolean = false
)

/**
 * One edition in the edition picker.
 *
 * @property isNewest the edition preselected at the top of the list.
 * @property isOlder a newer edition of the same source is installed; false for the newest and for
 *   editions with the same dates as it (see EditionOrder).
 */
data class EditionOption(
    val summary: SourceEditionSummary,
    val isNewest: Boolean,
    val isOlder: Boolean
)

class ProjectCreationViewModel : ViewModel(), KoinComponent {

    private val resourceMetadataRepository: IResourceMetadataRepository by inject()
    private val languageRepository: ILanguageRepository by inject()
    private val collectionRepository: ICollectionRepository by inject()
    private val createProject: CreateProject by inject()
    private val importer: ImportProjectUseCase by inject()
    private val describeSourceEditions: DescribeSourceEditions by inject()

    private val _uiState = MutableStateFlow(ProjectCreationUiState())
    val uiState: StateFlow<ProjectCreationUiState> = _uiState.asStateFlow()

    init {
        loadSources()
        loadTargetLanguages()
    }

    private fun loadSources() {
        launchLogged {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Imported sources (already in the DB) + bundled gateway sources that can be
                // sideloaded on demand. getAvailableGatewaySources returns only gateway
                // languages whose source zip is actually bundled (LanguageRepository consults
                // the build-generated manifest), so every entry here is sideloadable.
                val (sources, available) = withContext(Dispatchers.IO) {
                    // One entry per source: the newest installed edition. Choosing among
                    // editions comes with the edition picker.
                    val imported = resourceMetadataRepository.getAllSources().blockingGet()
                        .sortedWith(EditionOrder.newestFirst)
                        .distinctBy { it.language.slug to it.identifier }
                    val importedLangs = imported.map { it.language.slug }.toSet()
                    val available = languageRepository.getAvailableGatewaySources().blockingGet()
                        .filter { it.slug !in importedLangs }
                    imported to available
                }
                _uiState.update {
                    it.copy(sources = sources, availableSources = available, isLoading = false)
                }
            } catch (e: Exception) {
                logFailure("loading sources", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun loadTargetLanguages() {
        launchLogged {
            try {
                val languages = languageRepository.getAll().blockingGet() // Assuming getAll() derived from IRepository implies basic fetch
                _uiState.update { it.copy(targetLanguages = languages) }
            } catch (e: Exception) {
                logFailure("loading target languages", e)
            }
        }
    }

    /**
     * Choosing a source: when more than one edition of it is installed, the user picks one next
     * (newest first); otherwise the wizard goes straight on with it.
     */
    fun selectSource(source: ResourceMetadata) {
        launchLogged {
            val options = withContext(Dispatchers.IO) { editionOptions(source) }
            if (options.size > 1) {
                _uiState.update { it.copy(editions = options, currentStep = WizardStep.EDITION) }
            } else {
                _uiState.update {
                    it.copy(editions = emptyList(), selectedSource = source, currentStep = WizardStep.TARGET_LANGUAGE)
                }
            }
        }
    }

    fun selectEdition(edition: ResourceMetadata) {
        _uiState.update { it.copy(selectedSource = edition, currentStep = WizardStep.TARGET_LANGUAGE) }
    }

    private suspend fun editionOptions(source: ResourceMetadata): List<EditionOption> {
        val editions = resourceMetadataRepository.getAllSourcesSuspend()
            .filter { it.language.slug == source.language.slug && it.identifier == source.identifier }
            .sortedWith(EditionOrder.newestFirst)
        val summaries = describeSourceEditions.describeAll(editions)
        val newest = editions.firstOrNull()
        return editions.mapNotNull { edition ->
            summaries[edition.id]?.let { summary ->
                EditionOption(
                    summary = summary,
                    isNewest = edition.id == newest?.id,
                    isOlder = newest != null && EditionOrder.isNewer(newest, edition)
                )
            }
        }
    }

    /**
     * Selecting a not-yet-imported gateway source: sideload (import) its bundled zip, then
     * proceed as if the resulting source metadata had been picked. Mirrors Orature's wizard,
     * where sources are imported on demand rather than all at app initialization.
     */
    fun selectAvailableSource(language: Language) {
        launchLogged {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val metadata = withContext(Dispatchers.IO) {
                    importer.sideloadSource(language).blockingAwait()
                    resourceMetadataRepository.getAllSources().blockingGet()
                        .filter { it.language.slug == language.slug }
                        .let(EditionOrder::newest)
                }
                if (metadata != null) {
                    _uiState.update {
                        it.copy(
                            sources = it.sources + metadata,
                            availableSources = it.availableSources.filterNot { l -> l.slug == language.slug },
                            selectedSource = metadata,
                            currentStep = WizardStep.TARGET_LANGUAGE,
                            isLoading = false
                        )
                    }
                } else {
                    val msg = getString(Res.string.import_failed)
                    _uiState.update { it.copy(isLoading = false, error = msg) }
                }
            } catch (e: Exception) {
                logFailure("selecting an available source", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectTarget(language: Language) {
        _uiState.update { it.copy(selectedTarget = language, currentStep = WizardStep.BOOK) }
        loadAvailableBooks()
    }

    private fun loadAvailableBooks() {
        val source = _uiState.value.selectedSource ?: return
        launchLogged {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // We need to find the project collection for this metadata to get its children
                // In Otter, source content is organized as collections.
                // We'll search for the collection that matches this metadata.
                // Or if the source IS the collection, we verify that.
                
                // For simplicity assuming we can get the root source collection from the metadata
                // But typically we query the collection repository.
                // Let's assume we find the project collection by its slug/metadata.
                // However, CreateProject logic often iterates root sources.
                
                // Strategy: Find the collection that corresponds to this source metadata.
                val rootCollection = collectionRepository.getRootSources().blockingGet()
                    .find { it.resourceContainer?.id == source.id }
                
                if (rootCollection != null) {
                    val books = collectionRepository.getChildren(rootCollection).blockingGet()
                    _uiState.update { it.copy(availableBooks = books, isLoading = false) }
                } else {
                     _uiState.update { it.copy(isLoading = false, error = getString(Res.string.err_source_collection_not_found)) }
                }

            } catch (e: Exception) {
                logFailure("loading available books", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun selectBook(book: Collection) {
        _uiState.update { it.copy(selectedBook = book) }
        createWorkbook()
    }

    private fun createWorkbook() {
        val state = _uiState.value
        val source = state.selectedBook ?: return // This is the book collection in source language
        val sourceLang = state.selectedSource?.language ?: return
        val targetLang = state.selectedTarget ?: return

        // Recording in the same language as the source is narration; recording into a
        // different language is a dialect project.
        val mode = if (sourceLang.slug == targetLang.slug) {
            ProjectMode.NARRATION
        } else {
            ProjectMode.DIALECT
        }

        launchLogged {
            _uiState.update { it.copy(isLoading = true) }
            try {
                withContext(Dispatchers.IO) {
                    createProject.create(
                        sourceProject = source,
                        targetLanguage = targetLang,
                        mode = mode,
                        // MUST stay true, and the two arguments are independent: `mode` does not
                        // imply verses. Only `createAllBooks` couples them, so NARRATION with this
                        // false derives a target book with chapters and NO verse rows — the project
                        // opens, the chapter list populates, and every chapter is empty with nothing
                        // to record into. It was false between b1ccf32 and here (flipped in the same
                        // hunk that moved this call onto Dispatchers.IO, then kept by the merge in
                        // 25537bd over main's true), which is what made a freshly created project
                        // have no verses. `ProjectCreateTest` pins both paths and its default mirrors
                        // this call — but it passes its own argument, so it cannot catch this line
                        // changing.
                        deriveProjectFromVerses = true
                    ).blockingGet()
                }
                _uiState.update { it.copy(isLoading = false, isCreated = true) }
            } catch (e: Exception) {
                logFailure("creating the workbook", e)
                 _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
    
    fun navigateBack() {
        _uiState.update { 
            when (it.currentStep) {
                WizardStep.BOOK -> it.copy(currentStep = WizardStep.TARGET_LANGUAGE, selectedTarget = null, availableBooks = emptyList())
                WizardStep.TARGET_LANGUAGE -> it.copy(
                    currentStep = if (it.editions.size > 1) WizardStep.EDITION else WizardStep.SOURCE,
                    selectedSource = null
                )
                WizardStep.EDITION -> it.copy(currentStep = WizardStep.SOURCE, editions = emptyList())
                WizardStep.SOURCE -> it // Should be handled by UI to pop stack
            }
        }
    }
}
