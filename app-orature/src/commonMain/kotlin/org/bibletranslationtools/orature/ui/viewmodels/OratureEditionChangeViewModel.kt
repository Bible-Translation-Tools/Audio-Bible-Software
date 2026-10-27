package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.collections.EditionChoice
import org.bibletranslationtools.otter.common.domain.collections.ProjectUpgradePlan
import org.bibletranslationtools.otter.common.domain.collections.UpgradeProjectEdition

/**
 * What an edition change applies to: every book of a project (O1-Q1), or one book.
 *
 * @property projectBookIds the books' project collections (the descriptors' target collections).
 * @property editions the editions those books are on now.
 * @property bookTitles each book's title, by project collection id.
 * @property singleBook it is one book, from the book's own menu; the preview then lists every
 *   chapter that changes, where a whole project's lists only the books whose structure changes.
 */
data class OratureEditionChangeTarget(
    val projectBookIds: List<Int>,
    val editions: List<ResourceMetadata>,
    val bookTitles: Map<Int, String>,
    val singleBook: Boolean
)

sealed interface OratureEditionChangeState {
    data object Loading : OratureEditionChangeState
    data class Choosing(val choices: List<EditionChoice>, val selected: EditionChoice?) : OratureEditionChangeState
    data class Planning(val choice: EditionChoice, val checked: Int, val total: Int) : OratureEditionChangeState
    data class Previewing(val choices: List<EditionChoice>, val choice: EditionChoice, val plan: ProjectUpgradePlan) : OratureEditionChangeState
    data class Applying(val choice: EditionChoice, val done: Int, val total: Int) : OratureEditionChangeState
    data class Done(val choice: EditionChoice, val plan: ProjectUpgradePlan) : OratureEditionChangeState
    data class Error(val message: String?) : OratureEditionChangeState
}

/**
 * Moving a project, or one of its books, to another installed edition of its source: choose,
 * preview what happens to each book and chapter, apply. Upgrades and downgrades are the same
 * flow, and it runs from the home screen only, with no project open (O1-Q4).
 */
class OratureEditionChangeViewModel(
    private val target: OratureEditionChangeTarget,
    private val upgradeProjectEdition: UpgradeProjectEdition
) : ViewModel() {

    private val _state = MutableStateFlow<OratureEditionChangeState>(OratureEditionChangeState.Loading)
    val state: StateFlow<OratureEditionChangeState> = _state.asStateFlow()

    init {
        launchLogged {
            _state.value = attempt("loading the source editions") {
                val choices = withContext(Dispatchers.IO) { upgradeProjectEdition.choices(target.editions) }
                // An upgrade is preselected; a downgrade is always chosen on purpose.
                OratureEditionChangeState.Choosing(choices, choices.firstOrNull { it.newer })
            }
        }
    }

    fun select(choice: EditionChoice) {
        val choosing = _state.value as? OratureEditionChangeState.Choosing ?: return
        _state.value = choosing.copy(selected = choice)
    }

    fun preview() {
        val choosing = _state.value as? OratureEditionChangeState.Choosing ?: return
        val choice = choosing.selected ?: return
        _state.value = OratureEditionChangeState.Planning(choice, 0, target.projectBookIds.size)
        launchLogged {
            _state.value = attempt("planning the edition change") {
                val plan = withContext(Dispatchers.IO) {
                    upgradeProjectEdition.plan(target.projectBookIds, choice.edition) { checked, total ->
                        _state.value = OratureEditionChangeState.Planning(choice, checked, total)
                    }
                }
                OratureEditionChangeState.Previewing(choosing.choices, choice, plan)
            }
        }
    }

    fun back() {
        val previewing = _state.value as? OratureEditionChangeState.Previewing ?: return
        _state.value = OratureEditionChangeState.Choosing(previewing.choices, previewing.choice)
    }

    fun apply() {
        val previewing = _state.value as? OratureEditionChangeState.Previewing ?: return
        _state.value = OratureEditionChangeState.Applying(previewing.choice, 0, previewing.plan.books.size)
        launchLogged {
            _state.value = attempt("changing the edition") {
                withContext(Dispatchers.IO) {
                    upgradeProjectEdition.apply(previewing.plan) { done, total ->
                        _state.value = OratureEditionChangeState.Applying(previewing.choice, done, total)
                    }
                }
                OratureEditionChangeState.Done(previewing.choice, previewing.plan)
            }
        }
    }

    private suspend fun attempt(operation: String, block: suspend () -> OratureEditionChangeState): OratureEditionChangeState =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(operation, e)
            OratureEditionChangeState.Error(e.message)
        }
}
