package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bibletranslationtools.shared.preferences.ActiveNavState
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.main_chapter_label
import org.bibletranslationtools.shared.resources.main_verse_label

data class MainMenuUiState(
    val languageDisplay: String = "",
    val bookDisplay: String = "",
    val chapterDisplay: String = "",
    val unitDisplay: String = "",
    val hasActiveProject: Boolean = false
)

class MainMenuViewModel : ViewModel(), KoinComponent {

    private val appPreferences: IAppPreferences by inject()
    private val collectionRepository: ICollectionRepository by inject()
    private val workbookRepository: IWorkbookRepository by inject()

    val navState: StateFlow<ActiveNavState> = appPreferences.navState
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActiveNavState())

    private val _uiState = MutableStateFlow(MainMenuUiState())
    val uiState: StateFlow<MainMenuUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.navState.collect { state ->
                if (state.hasActiveWorkbook) {
                    loadDisplayInfo(state)
                } else {
                    _uiState.value = MainMenuUiState()
                }
            }
        }
    }

    private suspend fun loadDisplayInfo(state: ActiveNavState) {
        try {
            val sourceC = collectionRepository.getProjectSuspend(state.workbookSourceId) ?: return
            val targetC = collectionRepository.getProjectSuspend(state.workbookTargetId) ?: return
            val workbook = withContext(Dispatchers.IO) { workbookRepository.get(sourceC, targetC) } ?: return
            _uiState.value = MainMenuUiState(
                hasActiveProject = true,
                languageDisplay = workbook.target.language.name,
                bookDisplay = workbook.target.title,
                chapterDisplay = if (state.chapterSort != -1) getString(Res.string.main_chapter_label, state.chapterSort.toString()) else "",
                unitDisplay = if (state.unitSort != -1) getString(Res.string.main_verse_label, state.unitSort.toString()) else ""
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }
}
