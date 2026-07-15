package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.primitives.Anthology
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDateTime

/**
 * The grouping key used by the real Orature app (HomePageViewModel2.updateBookList):
 * source language + target language + source metadata (resource) slug + mode. All books
 * sharing this key are one project group / one card in the projects pane.
 */
data class OratureProjectGroupKey(
    val sourceLanguageSlug: String,
    val targetLanguageSlug: String,
    val resourceSlug: String,
    val mode: ProjectMode
)

/** One row in the book table (a single WorkbookDescriptor, mapped for display). */
data class OratureBookUiModel(
    val id: Int,
    val slug: String,
    val title: String,
    val anthology: Anthology,
    val progress: Double,
    val sort: Int,
    /** The project mode — routes book-open to the narration vs translation page. */
    val mode: ProjectMode
)

/** One project-group card in the projects pane (mirrors ProjectGroupCardModel). */
data class OratureProjectGroupUiModel(
    val key: OratureProjectGroupKey,
    val sourceLanguageName: String,
    val targetLanguageName: String,
    val mode: ProjectMode,
    val resourceSlug: String,
    val modifiedTs: LocalDateTime?,
    val books: List<OratureBookUiModel>
)

data class OratureHomeUiState(
    val isLoading: Boolean = true,
    val projectGroups: List<OratureProjectGroupUiModel> = emptyList(),
    val selectedGroupKey: OratureProjectGroupKey? = null,
    val bookSearchQuery: String = "",
    val error: String? = null
) {
    val isEmptyGroups: Boolean get() = !isLoading && projectGroups.isEmpty() && error == null

    val selectedGroup: OratureProjectGroupUiModel?
        get() = projectGroups.find { it.key == selectedGroupKey }

    /** The selected group's books, filtered by [bookSearchQuery] (title or slug, case-insensitive). */
    val visibleBooks: List<OratureBookUiModel>
        get() {
            val books = selectedGroup?.books ?: return emptyList()
            if (bookSearchQuery.isBlank()) return books
            return books.filter {
                it.title.contains(bookSearchQuery, ignoreCase = true) ||
                    it.slug.contains(bookSearchQuery, ignoreCase = true)
            }
        }
}

/**
 * Orature's real home-screen data source: loads all [WorkbookDescriptor]s and groups them
 * exactly like the JVM app's HomePageViewModel2.updateBookList — by
 * (sourceLanguage.slug, targetLanguage.slug, source resource-metadata slug, mode). Each
 * group becomes a project-group card; the selected group's books populate the book table.
 */
class OratureHomeViewModel : ViewModel(), KoinComponent {

    private val workbookDescriptorRepository: IWorkbookDescriptorRepository by inject()
    private val importEvents: OratureImportEvents by inject()

    private val _uiState = MutableStateFlow(OratureHomeUiState())
    val uiState: StateFlow<OratureHomeUiState> = _uiState.asStateFlow()

    init {
        loadProjects()
        // Refresh when a project is imported via the global Add Files drawer.
        viewModelScope.launch { importEvents.imported.collect { loadProjects() } }
    }

    /** Reload projects, selecting the most-recently-modified group (the default landing state). */
    fun loadProjects() = reloadProjects { groups -> groups.firstOrNull()?.key }

    /**
     * Reload projects after the wizard created (or matched) a project, then reselect THAT
     * group — mirroring the JVM app's `bookMarkedProjectGroupProperty ?: mostRecent` logic
     * in HomePageViewModel2.updateBookList. Falls back to the most-recent group if no group
     * matches (e.g. the created group somehow isn't present yet).
     */
    fun selectCreatedProject(created: OratureCreatedProject) = reloadProjects { groups ->
        groups.firstOrNull { group ->
            group.key.sourceLanguageSlug == created.sourceLanguageSlug &&
                group.key.targetLanguageSlug == created.targetLanguageSlug &&
                group.key.mode == created.mode &&
                (created.resourceSlug == null || group.key.resourceSlug == created.resourceSlug)
        }?.key ?: groups.firstOrNull()?.key
    }

    private fun reloadProjects(
        select: (List<OratureProjectGroupUiModel>) -> OratureProjectGroupKey?
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val descriptors = workbookDescriptorRepository.getAllSuspend()
                val groups = buildProjectGroups(descriptors)
                _uiState.value = OratureHomeUiState(
                    isLoading = false,
                    projectGroups = groups,
                    selectedGroupKey = select(groups)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    private suspend fun buildProjectGroups(
        descriptors: List<WorkbookDescriptor>
    ): List<OratureProjectGroupUiModel> {
        val bookModels = descriptors.map { it to it.toBookUiModel() }

        return bookModels
            .groupBy { (descriptor, _) -> descriptor.groupKey() }
            .map { (key, entries) ->
                val descriptorsInGroup = entries.map { it.first }
                val booksInGroup = entries.map { it.second }
                val template = descriptorsInGroup.first()
                val mostRecent = descriptorsInGroup
                    .filter { it.lastModified != null }
                    .maxByOrNull { it.lastModified!! }

                OratureProjectGroupUiModel(
                    key = key,
                    sourceLanguageName = template.sourceLanguage.anglicizedName,
                    targetLanguageName = template.targetLanguage.anglicizedName,
                    mode = template.mode,
                    resourceSlug = key.resourceSlug,
                    modifiedTs = mostRecent?.lastModified,
                    books = booksInGroup.sortedBy { it.sort }
                )
            }
            // Most-recently-modified group first, matching HomePageViewModel2. Groups
            // with no modification timestamp sort last.
            .sortedWith(compareBy<OratureProjectGroupUiModel> { it.modifiedTs == null }.thenByDescending { it.modifiedTs })
    }

    private fun WorkbookDescriptor.groupKey(): OratureProjectGroupKey {
        val resourceSlug = sourceCollection.resourceContainer?.identifier
            ?: sourceCollection.slug
        return OratureProjectGroupKey(
            sourceLanguageSlug = sourceLanguage.slug,
            targetLanguageSlug = targetLanguage.slug,
            resourceSlug = resourceSlug,
            mode = mode
        )
    }

    private suspend fun WorkbookDescriptor.toBookUiModel(): OratureBookUiModel {
        val resolvedProgress = try {
            progress.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0.0
        }
        return OratureBookUiModel(
            id = id,
            slug = slug,
            title = title,
            anthology = anthology,
            progress = resolvedProgress,
            sort = sort,
            mode = mode
        )
    }

    fun onSelectProjectGroup(key: OratureProjectGroupKey) {
        _uiState.value = _uiState.value.copy(selectedGroupKey = key, bookSearchQuery = "")
    }

    fun onBookSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(bookSearchQuery = query)
    }

    fun onBookClick(book: OratureBookUiModel) {
        // Phase 4 opens the project's chapter/verse view. Stub for now.
        println("Orature: book clicked (stub) — id=${book.id}, title=${book.title}")
    }

    fun onNewProjectClick() {
        // Phase 3 wires up the project creation wizard. Stub for now.
        println("Orature: new project clicked (stub)")
    }

}
