package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.bibletranslationtools.shared.preferences.ActiveNavState
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
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

    // Eager so active-nav is current as soon as the menu composes (Record reads it).
    val navState: StateFlow<ActiveNavState> = appPreferences.navState
        .stateIn(viewModelScope, SharingStarted.Eagerly, ActiveNavState())

    private val _uiState = MutableStateFlow(MainMenuUiState())
    val uiState: StateFlow<MainMenuUiState> = _uiState.asStateFlow()

    init {
        launchLogged {
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
            // Read from the project's book rather than its workbook: the main menu is outside the
            // project, so it must not open one (leaving the project closes its workbook).
            collectionRepository.getProjectSuspend(state.workbookSourceId) ?: return
            val targetC = collectionRepository.getProjectSuspend(state.workbookTargetId) ?: return
            val language = targetC.resourceContainer?.language ?: return
            _uiState.value = MainMenuUiState(
                hasActiveProject = true,
                languageDisplay = language.name,
                bookDisplay = targetC.titleKey,
                chapterDisplay = if (state.chapterSort != -1) getString(Res.string.main_chapter_label, state.chapterSort.toString()) else "",
                unitDisplay = if (state.unitSort != -1) getString(Res.string.main_verse_label, state.unitSort.toString()) else ""
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure("loading the main menu display info", e)
        }
    }
}
