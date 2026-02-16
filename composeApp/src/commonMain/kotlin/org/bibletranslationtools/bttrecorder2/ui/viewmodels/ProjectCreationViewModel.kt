package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.ILanguageRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IResourceMetadataRepository
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.collections.CreateProject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

enum class WizardStep {
    SOURCE,
    TARGET_LANGUAGE,
    BOOK
}

data class ProjectCreationUiState(
    val currentStep: WizardStep = WizardStep.SOURCE,
    val sources: List<ResourceMetadata> = emptyList(),
    val targetLanguages: List<Language> = emptyList(),
    val availableBooks: List<Collection> = emptyList(),
    val selectedSource: ResourceMetadata? = null,
    val selectedTarget: Language? = null,
    val selectedBook: Collection? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isCreated: Boolean = false
)

class ProjectCreationViewModel : ViewModel(), KoinComponent {

    private val resourceMetadataRepository: IResourceMetadataRepository by inject()
    private val languageRepository: ILanguageRepository by inject()
    private val collectionRepository: ICollectionRepository by inject()
    private val createProject: CreateProject by inject()

    private val _uiState = MutableStateFlow(ProjectCreationUiState())
    val uiState: StateFlow<ProjectCreationUiState> = _uiState.asStateFlow()

    init {
        loadSources()
        loadTargetLanguages()
    }

    private fun loadSources() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Fetch root sources (gateway sources)
                // Using getAllSources() from IResourceMetadataRepository or similar based on Otter usage
                val sources = resourceMetadataRepository.getAllSources().blockingGet()
                _uiState.update { it.copy(sources = sources, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun loadTargetLanguages() {
        viewModelScope.launch {
            try {
                val languages = languageRepository.getAll().blockingGet() // Assuming getAll() derived from IRepository implies basic fetch
                _uiState.update { it.copy(targetLanguages = languages) }
            } catch (e: Exception) {
                // Log error or handle gracefully
            }
        }
    }

    fun selectSource(source: ResourceMetadata) {
        _uiState.update { it.copy(selectedSource = source, currentStep = WizardStep.TARGET_LANGUAGE) }
    }

    fun selectTarget(language: Language) {
        _uiState.update { it.copy(selectedTarget = language, currentStep = WizardStep.BOOK) }
        loadAvailableBooks()
    }

    private fun loadAvailableBooks() {
        val source = _uiState.value.selectedSource ?: return
        viewModelScope.launch {
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
                     _uiState.update { it.copy(isLoading = false, error = "Source collection not found") }
                }

            } catch (e: Exception) {
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
        val targetLang = state.selectedTarget ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                createProject.create(
                    sourceProject = source,
                    targetLanguage = targetLang,
                    mode = ProjectMode.NARRATION,
                    deriveProjectFromVerses = true
                ).blockingGet()
                _uiState.update { it.copy(isLoading = false, isCreated = true) }
            } catch (e: Exception) {
                 _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
    
    fun navigateBack() {
        _uiState.update { 
            when (it.currentStep) {
                WizardStep.BOOK -> it.copy(currentStep = WizardStep.TARGET_LANGUAGE, selectedTarget = null, availableBooks = emptyList())
                WizardStep.TARGET_LANGUAGE -> it.copy(currentStep = WizardStep.SOURCE, selectedSource = null)
                WizardStep.SOURCE -> it // Should be handled by UI to pop stack
            }
        }
    }
}
