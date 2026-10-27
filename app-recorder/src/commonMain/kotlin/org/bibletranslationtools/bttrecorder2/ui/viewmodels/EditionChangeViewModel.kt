package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.bibletranslationtools.otter.common.domain.collections.BookEditionState
import org.bibletranslationtools.otter.common.domain.collections.BookUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.EditionChoice
import org.bibletranslationtools.otter.common.domain.collections.UpgradeBookEdition
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.err_project_not_found
import org.bibletranslationtools.shared.resources.err_unknown
import org.jetbrains.compose.resources.getString

/**
 * Moving one project book to another installed edition of its source: choose the edition, preview
 * what happens to each chapter, apply. Upgrades and downgrades are the same flow; there is no undo,
 * since moving back is another change of edition and takes are never deleted (S9-Q2, S9-Q3).
 *
 * @param projectBookId the project's book collection (the workbook's target).
 */
class EditionChangeViewModel(
    private val projectBookId: Int,
    private val upgradeBookEdition: UpgradeBookEdition,
    private val appPreferences: IAppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow<EditionChangeState>(EditionChangeState.Loading)
    val state: StateFlow<EditionChangeState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        launchLogged {
            _state.value = try {
                val book = upgradeBookEdition.editionState(projectBookId)
                if (book == null) {
                    EditionChangeState.Error(getString(Res.string.err_project_not_found))
                } else {
                    // Preselect the newest edition when it is an upgrade; a downgrade is always chosen on purpose.
                    EditionChangeState.Choosing(book, book.choices.firstOrNull { it.newer })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading the book's editions", e)
                EditionChangeState.Error(e.message ?: getString(Res.string.err_unknown))
            }
        }
    }

    fun select(choice: EditionChoice) {
        val current = _state.value as? EditionChangeState.Choosing ?: return
        _state.value = current.copy(selected = choice)
    }

    /** Plans the move to the selected edition and shows the preview. */
    fun preview() {
        val choosing = _state.value as? EditionChangeState.Choosing ?: return
        val choice = choosing.selected ?: return
        _state.value = EditionChangeState.Planning(choosing.book, choice)
        launchLogged {
            _state.value = try {
                EditionChangeState.Previewing(choosing.book, choice, upgradeBookEdition.plan(projectBookId, choice.edition))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("planning the edition change", e)
                EditionChangeState.Error(e.message ?: getString(Res.string.err_unknown))
            }
        }
    }

    /** From the preview back to choosing. */
    fun back() {
        val previewing = _state.value as? EditionChangeState.Previewing ?: return
        _state.value = EditionChangeState.Choosing(previewing.book, previewing.choice)
    }

    fun apply() {
        val previewing = _state.value as? EditionChangeState.Previewing ?: return
        _state.value = EditionChangeState.Applying(previewing.choice)
        launchLogged {
            _state.value = try {
                upgradeBookEdition.apply(previewing.plan)
                followActiveWorkbook(previewing.plan)
                EditionChangeState.Done(previewing.choice, previewing.plan.heldBack.map { it.sort })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("changing the book's edition", e)
                EditionChangeState.Error(e.message ?: getString(Res.string.err_unknown))
            }
        }
    }

    /**
     * The active workbook is saved by its source and target ids, and the source is now another
     * book. Point it at the new one, keeping the chapter the user was in.
     */
    private suspend fun followActiveWorkbook(plan: BookUpgradePlan) {
        val nav = appPreferences.navState.first()
        if (!nav.hasActiveWorkbook || nav.workbookTargetId != projectBookId) return
        appPreferences.setActiveWorkbook(plan.toSourceBookId, projectBookId)
        if (nav.hasActiveChapter) appPreferences.setActiveChapter(nav.chapterSort)
    }
}

sealed interface EditionChangeState {
    data object Loading : EditionChangeState
    data class Choosing(val book: BookEditionState, val selected: EditionChoice?) : EditionChangeState
    data class Planning(val book: BookEditionState, val choice: EditionChoice) : EditionChangeState
    data class Previewing(val book: BookEditionState, val choice: EditionChoice, val plan: BookUpgradePlan) : EditionChangeState
    data class Applying(val choice: EditionChoice) : EditionChangeState
    /** @property heldBack numbers of the chapters that kept their verse structure. */
    data class Done(val choice: EditionChoice, val heldBack: List<Int>) : EditionChangeState
    data class Error(val message: String) : EditionChangeState
}
