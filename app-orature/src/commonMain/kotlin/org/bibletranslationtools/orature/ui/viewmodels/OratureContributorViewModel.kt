package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.domain.project.OpenWorkbook
import org.bibletranslationtools.otter.common.data.primitives.Contributor
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** UI state for the contributor dialog (JVM: `ContributorDialog` + HomePageViewModel2 contributors). */
data class OratureContributorUiState(
    val isLoading: Boolean = true,
    /** Contributor display names, in order. */
    val contributors: List<String> = emptyList()
)

/**
 * Drives the "Modify Contributors" dialog (JVM: `ContributorDialog`): loads the project's contributor
 * names from its resource-container manifest (dublinCore.contributor) and saves edits back. Add /
 * edit / remove operate in memory; [save] persists via [ProjectFilesAccessor.setContributorInfo].
 */
class OratureContributorViewModel(
    private val workbookDescriptorId: Int
) : ViewModel(), KoinComponent {

    private val openWorkbook: OpenWorkbook by inject()

    private val _uiState = MutableStateFlow(OratureContributorUiState())
    val uiState: StateFlow<OratureContributorUiState> = _uiState.asStateFlow()

    private var workbook: Workbook? = null

    init {
        load()
    }

    private fun load() {
        launchLogged {
            try {
                // open(), not openWithChapters(): this screen lists contributor names and never
                // renders a chapter list, so there is no reason to pay a completion lookup per
                // chapter for it.
                val wb = openWorkbook.open(workbookDescriptorId).workbook
                workbook = wb
                val names = withContext(Dispatchers.IO) {
                    wb.projectFilesAccessor.getContributorInfo().map { it.name }
                }
                _uiState.value = OratureContributorUiState(isLoading = false, contributors = names)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading contributors", e)
                _uiState.value = OratureContributorUiState(isLoading = false, contributors = emptyList())
            }
        }
    }

    fun addContributor(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _uiState.value = _uiState.value.copy(contributors = _uiState.value.contributors + trimmed)
    }

    fun editContributor(index: Int, name: String) {
        val trimmed = name.trim()
        _uiState.value = _uiState.value.copy(
            contributors = _uiState.value.contributors.toMutableList().also {
                if (index in it.indices) it[index] = trimmed
            }
        )
    }

    fun removeContributor(index: Int) {
        _uiState.value = _uiState.value.copy(
            contributors = _uiState.value.contributors.toMutableList().also {
                if (index in it.indices) it.removeAt(index)
            }
        )
    }

    /** Persist the contributor list to the project manifest (JVM: saveContributors). */
    fun save() {
        val wb = workbook ?: return
        val contributors = _uiState.value.contributors.filter { it.isNotBlank() }.map { Contributor(it) }
        launchLogged {
            withContext(Dispatchers.IO) {
                runCatching { wb.projectFilesAccessor.setContributorInfo(contributors) }
                    .onFailure { System.err.println("Failed to save contributors: $it") }
            }
        }
    }
}
