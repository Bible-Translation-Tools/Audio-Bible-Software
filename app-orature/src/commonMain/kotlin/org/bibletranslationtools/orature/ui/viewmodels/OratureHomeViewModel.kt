package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookDescriptorRepository
import org.bibletranslationtools.otter.common.data.primitives.Anthology
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDateTime

/** The undo window before a group delete is permanent (JVM: NOTIFICATION_DURATION_SEC). */
private const val GROUP_DELETE_UNDO_MS = 5000L

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
    /** True when the book has source audio available (JVM: shows the speaker status icon). */
    val hasSourceAudio: Boolean,
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
    private val projectDeletion: OratureProjectDeletion by inject()

    private val _uiState = MutableStateFlow(OratureHomeUiState())
    val uiState: StateFlow<OratureHomeUiState> = _uiState.asStateFlow()

    // All groups from the last DB load + the descriptors behind them, so a group delete can be
    // resolved to its books and the group can be optimistically hidden during its undo window.
    private var allGroups: List<OratureProjectGroupUiModel> = emptyList()
    private var loadedDescriptors: List<WorkbookDescriptor> = emptyList()
    private val pendingDeleteKeys = mutableSetOf<OratureProjectGroupKey>()
    private val deleteJobs = mutableMapOf<OratureProjectGroupKey, Job>()
    // In-flight per-book progress computations from the current load (cancelled on the next reload).
    private val progressJobs = mutableListOf<Job>()

    init {
        loadProjects()
        // Refresh when a project is imported via the global Add Files drawer.
        launchLogged { importEvents.imported.collect { loadProjects() } }
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
        // Cancel any background computations still running from a previous load.
        progressJobs.forEach { it.cancel() }
        progressJobs.clear()

        launchLogged {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Skip source audio here: resolving it opens each source resource container (a zip),
                // ~hundreds of ms each, which blocked the list. Resolve it in the background below.
                // (getAllSuspend already runs its DB work on the Rx IO scheduler.)
                val t0 = System.currentTimeMillis()
                val descriptors = workbookDescriptorRepository.getAllSuspend(computeSourceAudio = false)
                System.err.println("[home-perf] getAllSuspend (${descriptors.size}, no source-audio) took ${System.currentTimeMillis() - t0}ms")
                // Phase A: build + publish the list IMMEDIATELY with progress (0.0) and source audio
                // (false) unresolved. The per-book progress scans and the source-RC zip opens are the
                // expensive parts; keeping them off the critical path is what makes the home page
                // appear at once. Mirrors the JVM HomePageViewModel2, which renders the list first.
                val groups = buildProjectGroups(descriptors)
                loadedDescriptors = descriptors
                allGroups = groups
                val visible = groups.filter { it.key !in pendingDeleteKeys }
                val selectedKey = select(visible)
                _uiState.value = OratureHomeUiState(
                    isLoading = false,
                    projectGroups = visible,
                    selectedGroupKey = selectedKey
                )
                // Phase B: resolve each book's progress in parallel and patch each ring as it resolves.
                computeProgressInBackground(descriptors)
                // Phase C: resolve source audio in the background — the selected (in-view) group's
                // books first so their speaker icons appear soonest, then the rest.
                resolveSourceAudioInBackground(descriptors, selectedKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("reloading the project list", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * Resolve hasSourceAudio off the critical path, in-view group first. The repository opens each
     * unique source resource container once per call, so this is at most a few zip opens total —
     * done here in the background instead of blocking the initial render.
     */
    private fun resolveSourceAudioInBackground(
        descriptors: List<WorkbookDescriptor>,
        selectedKey: OratureProjectGroupKey?
    ) {
        val (inView, rest) = descriptors.partition { it.groupKey() == selectedKey }
        val tStart = System.currentTimeMillis()
        listOf(inView to "in-view", rest to "rest").filter { it.first.isNotEmpty() }.forEach { (batch, label) ->
            val job = launchLogged {
                val byId = try {
                    workbookDescriptorRepository.getSourceAudioSuspend(batch) // resolves on Rx IO
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure("resolving source audio availability", e)
                    emptyMap()
                }
                System.err.println("[home-perf] source-audio $label (${batch.size}) resolved at +${System.currentTimeMillis() - tStart}ms")
                applyBookSourceAudio(byId)
            }
            progressJobs.add(job)
        }
    }

    /**
     * Compute each descriptor's progress concurrently (each [WorkbookDescriptor.progress] is a
     * Single that subscribes on the Rx IO pool, so N awaits run in parallel), then patch that book's
     * ring into the published state on the main dispatcher (serialized — no race on [allGroups]).
     */
    private fun computeProgressInBackground(descriptors: List<WorkbookDescriptor>) {
        descriptors.forEach { descriptor ->
            val job = launchLogged {
                val resolved = try {
                    descriptor.progress.await() // Single subscribes on the Rx IO scheduler
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure("computing project progress", e)
                    0.0
                }
                applyBookProgress(descriptor.id, resolved)
            }
            progressJobs.add(job)
        }
    }

    /** Patch a single book's resolved progress into [allGroups] and republish (main dispatcher). */
    private fun applyBookProgress(bookId: Int, progress: Double) {
        var changed = false
        allGroups = allGroups.map { group ->
            if (group.books.none { it.id == bookId }) return@map group
            changed = true
            group.copy(
                books = group.books.map { if (it.id == bookId) it.copy(progress = progress) else it }
            )
        }
        if (!changed) return
        val visible = allGroups.filter { it.key !in pendingDeleteKeys }
        _uiState.value = _uiState.value.copy(projectGroups = visible)
    }

    /** Patch resolved source-audio flags (bookId -> has) into [allGroups] and republish. */
    private fun applyBookSourceAudio(byId: Map<Int, Boolean>) {
        if (byId.isEmpty()) return
        allGroups = allGroups.map { group ->
            if (group.books.none { it.id in byId }) return@map group
            group.copy(
                books = group.books.map { book ->
                    byId[book.id]?.let { book.copy(hasSourceAudio = it) } ?: book
                }
            )
        }
        val visible = allGroups.filter { it.key !in pendingDeleteKeys }
        _uiState.value = _uiState.value.copy(projectGroups = visible)
    }

    private fun publishVisibleGroups() {
        val visible = allGroups.filter { it.key !in pendingDeleteKeys }
        val sel = _uiState.value.selectedGroupKey
        val newSel = if (sel != null && visible.any { it.key == sel }) sel else visible.firstOrNull()?.key
        _uiState.value = _uiState.value.copy(projectGroups = visible, selectedGroupKey = newSel)
    }

    /**
     * Start deleting a project group after an undo window (JVM: removeProjectFromList +
     * deleteProjectGroupWithTimer). The group is hidden immediately; if [undoGroupDelete] isn't called
     * within the window it's permanently deleted. A pending delete blocks project creation.
     */
    fun scheduleGroupDelete(key: OratureProjectGroupKey) {
        if (key in pendingDeleteKeys) return
        val descriptors = loadedDescriptors.filter { it.groupKey() == key }
        if (descriptors.isEmpty()) return
        pendingDeleteKeys.add(key)
        projectDeletion.beginPending()
        publishVisibleGroups()
        deleteJobs[key] = launchLogged {
            try {
                delay(GROUP_DELETE_UNDO_MS)
                if (key !in pendingDeleteKeys) return@launchLogged // undone during the window
                withContext(Dispatchers.IO) { projectDeletion.deleteGroup(descriptors) }
                pendingDeleteKeys.remove(key)
                loadProjects()
            } finally {
                deleteJobs.remove(key)
                projectDeletion.endPending()
            }
        }
    }

    /** Cancel a pending group delete and restore the group (JVM: undoDeleteProjectGroup). */
    fun undoGroupDelete(key: OratureProjectGroupKey) {
        pendingDeleteKeys.remove(key)
        deleteJobs.remove(key)?.cancel() // its finally decrements the pending counter
        publishVisibleGroups()
    }

    /** Reset a single book to its initial state (JVM: deleteBook — deletes takes, re-derives). */
    fun deleteBook(workbookDescriptorId: Int) {
        val descriptor = loadedDescriptors.firstOrNull { it.id == workbookDescriptorId } ?: return
        launchLogged {
            withContext(Dispatchers.IO) { projectDeletion.deleteBook(descriptor) }
            loadProjects()
        }
    }

    private fun buildProjectGroups(
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

    /** Build the display model WITHOUT resolving progress — progress is computed off the critical
     *  path in [computeProgressInBackground] and patched in as it completes. Starts at 0.0 (ring
     *  empty) so the list can render immediately. */
    private fun WorkbookDescriptor.toBookUiModel(): OratureBookUiModel =
        OratureBookUiModel(
            id = id,
            slug = slug,
            title = title,
            anthology = anthology,
            progress = 0.0,
            sort = sort,
            hasSourceAudio = hasSourceAudio,
            mode = mode
        )

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
