package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledResource
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledResources

/** The outcome of the last removal, for the drawer to report. */
sealed interface OratureResourceRemoval {
    val resource: InstalledResource
    data class Removed(override val resource: InstalledResource) : OratureResourceRemoval
    data class Failed(override val resource: InstalledResource) : OratureResourceRemoval
}

data class OratureResourcesUiState(
    val isLoading: Boolean = true,
    val resources: List<InstalledResource> = emptyList(),
    /** The resource being removed, by edition id. */
    val removingId: Int? = null,
    val lastRemoval: OratureResourceRemoval? = null,
    val error: String? = null
) {
    val totalBytes: Long get() = resources.sumOf { it.sizeBytes }
}

/**
 * The Resources drawer: the source texts and source audio installed on this device, what each
 * takes and what uses it, and removing one nothing uses. Finding and downloading resources comes
 * later.
 *
 * This outlives the drawer (it is scoped to the window, not to the drawer's composition), so the
 * drawer calls [onOpened] each time it opens. Loading once would keep showing what used each
 * resource when the drawer first opened, e.g. an edition still in use after every project moved off it.
 */
class OratureResourcesViewModel(private val installedResources: InstalledResources) : ViewModel() {

    private val _uiState = MutableStateFlow(OratureResourcesUiState())
    val uiState: StateFlow<OratureResourcesUiState> = _uiState.asStateFlow()

    /** Reloads the list, and drops the message about a removal made the last time it was open. */
    fun onOpened() {
        _uiState.value = _uiState.value.copy(lastRemoval = null)
        load()
    }

    private fun load() {
        launchLogged {
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.resources.isEmpty(), error = null)
            _uiState.value = try {
                _uiState.value.copy(isLoading = false, resources = installedResources.list())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("listing the installed resources", e)
                _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun remove(resource: InstalledResource) {
        if (!resource.removable || _uiState.value.removingId != null) return
        _uiState.value = _uiState.value.copy(removingId = resource.edition.id, lastRemoval = null)
        launchLogged {
            val removed = try {
                installedResources.remove(resource.edition)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("removing a resource", e)
                false
            }
            _uiState.value = _uiState.value.copy(
                removingId = null,
                lastRemoval = if (removed) OratureResourceRemoval.Removed(resource) else OratureResourceRemoval.Failed(resource)
            )
            load()
        }
    }
}
