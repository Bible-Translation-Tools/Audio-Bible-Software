package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.bibletranslationtools.bttrecorder2.ui.components.Breadcrumb
import org.bibletranslationtools.bttrecorder2.ui.components.CollapsibleCard
import org.bibletranslationtools.bttrecorder2.ui.components.EditionChangeDialog
import org.bibletranslationtools.bttrecorder2.ui.components.LazyColumnWithScrollbar
import org.bibletranslationtools.bttrecorder2.ui.components.ProgressBar
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectManagementTopBar
import org.bibletranslationtools.bttrecorder2.ui.components.SearchFilterBar
import org.bibletranslationtools.bttrecorder2.ui.components.SortHeaderCell
import org.bibletranslationtools.bttrecorder2.ui.components.localizedList
import org.bibletranslationtools.bttrecorder2.ui.components.sourceEditionText
import org.bibletranslationtools.bttrecorder2.ui.components.takeDateText
import org.bibletranslationtools.bttrecorder2.ui.components.typeLabel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListUiState
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterListViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ChapterUiModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProgressFilter
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.SortDirection
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.shownTake
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.domain.collections.BookEditionState
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_cancel
import org.bibletranslationtools.shared.resources.action_collapse
import org.bibletranslationtools.shared.resources.action_delete
import org.bibletranslationtools.shared.resources.action_ok
import org.bibletranslationtools.shared.resources.action_pause
import org.bibletranslationtools.shared.resources.action_play
import org.bibletranslationtools.shared.resources.cd_chapter_compiled
import org.bibletranslationtools.shared.resources.cd_compile_chapter
import org.bibletranslationtools.shared.resources.cd_compile_chapter_not_ready
import org.bibletranslationtools.shared.resources.cd_delete_chapter_take
import org.bibletranslationtools.shared.resources.cd_expand_chapter_take
import org.bibletranslationtools.shared.resources.cd_next_take
import org.bibletranslationtools.shared.resources.cd_open_in_playback
import org.bibletranslationtools.shared.resources.cd_previous_take
import org.bibletranslationtools.shared.resources.cd_record_chapter
import org.bibletranslationtools.shared.resources.cd_select_take
import org.bibletranslationtools.shared.resources.chapter_compile_warning_message
import org.bibletranslationtools.shared.resources.chapter_compile_warning_title
import org.bibletranslationtools.shared.resources.chapter_delete_take_message
import org.bibletranslationtools.shared.resources.chapter_delete_take_title
import org.bibletranslationtools.shared.resources.edition_banner_review
import org.bibletranslationtools.shared.resources.edition_banner_update
import org.bibletranslationtools.shared.resources.edition_held_back_message
import org.bibletranslationtools.shared.resources.error_prefix
import org.bibletranslationtools.shared.resources.label_earlier_structure
import org.bibletranslationtools.shared.resources.main_chapter_label
import org.bibletranslationtools.shared.resources.pm_language_pair
import org.bibletranslationtools.shared.resources.pm_search_chapters
import org.bibletranslationtools.shared.resources.pm_sort_chapter
import org.bibletranslationtools.shared.resources.pm_sort_progress
import org.bibletranslationtools.shared.resources.unit_take_position
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ChapterListScreen(
    viewModel: ChapterListViewModel = viewModel { ChapterListViewModel() },
    onBackClick: () -> Unit,
    onLanguagesClick: () -> Unit,
    onNewProjectClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onChapterClick: () -> Unit,
    onRecordChapter: (sourceId: Int, targetId: Int, chapterSort: Int) -> Unit = { _, _, _ -> },
    onOpenChapterPlayback: (sourceId: Int, targetId: Int, chapterSort: Int, takeNumber: Int) -> Unit =
        { _, _, _, _ -> }
) {
    LaunchedEffect(Unit) {
        viewModel.loadChapters()
    }

    val uiState by viewModel.uiState.collectAsState()

    // Confirmation-dialog targets. Both flows funnel through AlertDialogs so
    // destructive / heavy actions can't be triggered with a single tap.
    var pendingCompileChapter by remember { mutableStateOf<Chapter?>(null) }
    var pendingDeleteChapter by remember { mutableStateOf<Chapter?>(null) }
    var showEditionChange by remember { mutableStateOf(false) }

    ChapterListContent(
        uiState = uiState,
        onReviewEdition = { showEditionChange = true },
        onBackClick = onBackClick,
        onLanguagesClick = onLanguagesClick,
        onNewProjectClick = onNewProjectClick,
        onSettingsClick = onSettingsClick,
        onChapterClick = { sort -> viewModel.selectChapter(sort, onChapterClick) },
        onRecordChapter = { sort ->
            uiState.workbook?.let { wb ->
                viewModel.selectChapter(sort) { onRecordChapter(wb.source.collectionId, wb.target.collectionId, sort) }
            }
        },
        onCompileClick = { chapter -> pendingCompileChapter = chapter },
        onChapterExpand = viewModel::prepareChapterPlayback,
        onChapterPlayPause = viewModel::toggleChapterPlayback,
        onShowOtherTake = viewModel::showOtherTake,
        onSelectShownTake = viewModel::selectShownTake,
        onChapterDeleteRequest = { chapter -> pendingDeleteChapter = chapter },
        onOpenChapterPlayback = { sort, takeNumber ->
            uiState.workbook?.let { wb ->
                onOpenChapterPlayback(wb.source.collectionId, wb.target.collectionId, sort, takeNumber)
            }
        }
    )

    if (showEditionChange) {
        uiState.editionState?.let { editions ->
            EditionChangeDialog(
                projectBookId = editions.projectBookId,
                onDismiss = { showEditionChange = false },
                onChanged = viewModel::reload
            )
        }
    }

    pendingCompileChapter?.let { chapter ->
        AlertDialog(
            onDismissRequest = { pendingCompileChapter = null },
            title = { Text(stringResource(Res.string.chapter_compile_warning_title)) },
            text = { Text(stringResource(Res.string.chapter_compile_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.compileChapter(chapter)
                    pendingCompileChapter = null
                }) { Text(stringResource(Res.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCompileChapter = null }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }

    pendingDeleteChapter?.let { chapter ->
        AlertDialog(
            onDismissRequest = { pendingDeleteChapter = null },
            title = { Text(stringResource(Res.string.chapter_delete_take_title)) },
            text = { Text(stringResource(Res.string.chapter_delete_take_message, chapter.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChapterTake(chapter)
                    pendingDeleteChapter = null
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteChapter = null }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }
}

@Composable
fun ChapterListContent(
    uiState: ChapterListUiState,
    onBackClick: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onRecordChapter: (Int) -> Unit,
    onCompileClick: (Chapter) -> Unit = {},
    onChapterExpand: (Chapter) -> Unit = {},
    onChapterPlayPause: (Chapter) -> Unit = {},
    onShowOtherTake: (Chapter, direction: Int) -> Unit = { _, _ -> },
    onSelectShownTake: (Chapter) -> Unit = {},
    onChapterDeleteRequest: (Chapter) -> Unit = {},
    onOpenChapterPlayback: (chapterSort: Int, takeNumber: Int) -> Unit = { _, _ -> },
    onReviewEdition: () -> Unit = {},
    onLanguagesClick: () -> Unit = {},
    onNewProjectClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    // The row a user taps to expand. Only one expanded at a time, mirroring
    // the original Recorder behaviour.
    var expandedChapterSort by remember { mutableStateOf<Int?>(null) }
    var isCardExpanded by rememberSaveable { mutableStateOf(true) }
    var sortField by rememberSaveable { mutableStateOf(ChapterSortField.CHAPTER) }
    var sortDirection by rememberSaveable { mutableStateOf(SortDirection.ASC) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<ProgressFilter?>(null) }
    val chapters = remember(uiState.chapters, sortField, sortDirection) {
        val order = when (sortField) {
            ChapterSortField.CHAPTER -> compareBy<ChapterUiModel> { it.chapter.sort }
            ChapterSortField.PROGRESS -> compareBy { it.completion }
        }
        // Equal progress keeps chapter order whichever way it runs.
        uiState.chapters.sortedWith(
            (if (sortDirection == SortDirection.DESC) order.reversed() else order).thenBy { it.chapter.sort }
        )
    }

    Scaffold(
        topBar = {
            ProjectManagementTopBar(
                onNewProjectClick = onNewProjectClick,
                onSettingsClick = onSettingsClick,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                uiState.workbook?.let { wb ->
                    Breadcrumb(
                        stringResource(
                            Res.string.pm_language_pair,
                            wb.target.resourceMetadata.language.anglicizedName,
                            wb.source.resourceMetadata.language.anglicizedName
                        ) to onLanguagesClick,
                        typeLabel(wb.target.resourceMetadata.identifier) to onBackClick
                    )
                }
                SearchFilterBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(Res.string.pm_search_chapters),
                    filter = filter,
                    onFilterChange = { filter = it },
                    modifier = Modifier.weight(1f)
                )
            }
            // Search matches the label as shown ("Chapter 12"), so the number alone or with the word works.
            val labels = chapters.associate { it.chapter.sort to stringResource(Res.string.main_chapter_label, it.chapter.title) }
            val shownChapters = chapters.filter {
                labels.getValue(it.chapter.sort).contains(query.trim(), ignoreCase = true) &&
                    filter?.matches(it.completion.toDouble()) != false
            }
            // Hoisted above the `when` so the scroll position is retained across
            // load-state transitions and back-navigation (rememberLazyListState is
            // rememberSaveable-backed and restored per nav back-stack entry).
            val listState = rememberLazyListState()
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.error != null -> {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(Res.string.error_prefix, uiState.error ?: ""),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    uiState.editionState?.let { editions ->
                        if (editions.updateAvailable || editions.heldBackChapters.isNotEmpty()) {
                            EditionBanner(editions, onReviewEdition)
                        }
                    }
                    CollapsibleCard(
                        title = uiState.workbook?.target?.title.orEmpty(),
                        isExpanded = isCardExpanded,
                        onToggle = { isCardExpanded = !isCardExpanded },
                        // Shrinks to its rows, but never past the screen: the rows scroll instead.
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ChapterSortHeader(sortField, sortDirection) { field ->
                            if (field == sortField) {
                                sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                            } else {
                                sortField = field
                                sortDirection = SortDirection.ASC
                            }
                        }
                        if (shownChapters.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LazyColumnWithScrollbar(
                            state = listState,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            items(shownChapters, key = { it.chapter.sort }) { uiModel ->
                                val isLoadedHere =
                                    uiState.loadedChapterSort == uiModel.chapter.sort
                                val isPlayingThis = isLoadedHere && uiState.isChapterPlaying
                                val isCompilingThis =
                                    uiState.compilingChapterSort == uiModel.chapter.sort
                                val shownTake = uiModel.shownTake(uiState.shownTakeNumbers[uiModel.chapter.sort])
                                val isExpanded =
                                    expandedChapterSort == uiModel.chapter.sort && shownTake != null

                                ChapterItem(
                                    uiModel = uiModel,
                                    heldBack = uiState.editionState?.heldBackChapters?.contains(uiModel.chapter.sort) == true,
                                    isExpanded = isExpanded,
                                    shownTake = shownTake,
                                    isCompiling = isCompilingThis,
                                    isPlaying = isPlayingThis,
                                    // Use the loaded-here values so the slider /
                                    // duration label survive a pause (the take is
                                    // still in the player). When a different chapter
                                    // is loaded, fall back to zeros.
                                    playbackProgress = if (isLoadedHere) uiState.playbackProgress else 0f,
                                    elapsedText = if (isLoadedHere) uiState.elapsedText else "00:00:00",
                                    durationText = if (isLoadedHere) uiState.durationText else "00:00:00",
                                    onChapterClick = {
                                        // Tap on the row's text area opens the unit list, just like
                                        // the original. Expand/collapse is on the chevron.
                                        onChapterClick(uiModel.chapter.sort)
                                    },
                                    onRecordChapter = { onRecordChapter(uiModel.chapter.sort) },
                                    onCompileClick = {
                                        // Compile only when every verse has a take; the chevron
                                        // (not this icon) drives expand of an existing take.
                                        if (uiModel.canCompile && !isCompilingThis) {
                                            onCompileClick(uiModel.chapter)
                                        }
                                    },
                                    onExpandToggle = {
                                        val nowExpanding =
                                            expandedChapterSort != uiModel.chapter.sort
                                        expandedChapterSort =
                                            if (nowExpanding) uiModel.chapter.sort else null
                                        if (nowExpanding && shownTake != null) {
                                            // Eagerly load the take so duration shows
                                            // immediately instead of after the first
                                            // play tap.
                                            onChapterExpand(uiModel.chapter)
                                        }
                                    },
                                    onPlayPause = { onChapterPlayPause(uiModel.chapter) },
                                    onShowOtherTake = { direction -> onShowOtherTake(uiModel.chapter, direction) },
                                    onSelectTake = { onSelectShownTake(uiModel.chapter) },
                                    onDelete = { onChapterDeleteRequest(uiModel.chapter) },
                                    onOpenPlayback = {
                                        shownTake?.let { onOpenChapterPlayback(uiModel.chapter.sort, it.number) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ChapterSortField { CHAPTER, PROGRESS }

@Composable
private fun ChapterSortHeader(field: ChapterSortField, direction: SortDirection, onSort: (ChapterSortField) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SortHeaderCell(stringResource(Res.string.pm_sort_chapter), field == ChapterSortField.CHAPTER, direction) {
                onSort(ChapterSortField.CHAPTER)
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            SortHeaderCell(stringResource(Res.string.pm_sort_progress), field == ChapterSortField.PROGRESS, direction) {
                onSort(ChapterSortField.PROGRESS)
            }
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(48.dp))
    }
}

/**
 * Above the chapters: a newer edition of the book's source is installed, or some chapters keep the
 * verse structure they were recorded with. [onReview] opens the edition change flow.
 */
@Composable
private fun EditionBanner(editions: BookEditionState, onReview: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                editions.choices.firstOrNull { it.newer }?.let { newest ->
                    Text(
                        stringResource(Res.string.edition_banner_update, sourceEditionText(newest.edition, newest.distinguishingCode)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (editions.heldBackChapters.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.edition_held_back_message, localizedList(editions.heldBackChapters.map { it.toString() })),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            TextButton(onClick = onReview) { Text(stringResource(Res.string.edition_banner_review)) }
        }
    }
}

@Composable
fun ChapterItem(
    uiModel: ChapterUiModel,
    /** The chapter keeps an earlier edition's verse structure (held back on an upgrade). */
    heldBack: Boolean = false,
    isExpanded: Boolean,
    /** The take the expanded player shows; null when the chapter has no take. */
    shownTake: Take? = null,
    isCompiling: Boolean,
    isPlaying: Boolean,
    playbackProgress: Float,
    elapsedText: String,
    durationText: String,
    onChapterClick: () -> Unit,
    onRecordChapter: () -> Unit,
    onCompileClick: () -> Unit,
    onExpandToggle: () -> Unit,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
    onShowOtherTake: (direction: Int) -> Unit = {},
    onSelectTake: () -> Unit = {},
    onOpenPlayback: () -> Unit = {}
) {
    // Un-gray the chapter when it has any recorded audio — either a compiled/recorded
    // chapter take or at least one verse take.
    val hasAnyTake = uiModel.hasContent || uiModel.hasChapterTake || uiModel.takes.isNotEmpty()

    // The layers icon is a *compile* action, available ONLY when every verse has a
    // selected take (canCompile). The existence of a chapter take is irrelevant to
    // whether compile is offered — you can't compile a chapter without all its
    // verses, and a chapter take alone must not make it look available.
    //   - solid + enabled: every verse recorded → tap to (re)compile.
    //   - dimmed + disabled: some verse is missing a take.
    // Expand/collapse of an existing chapter take lives on the separate chevron.
    val layersAlpha = if (uiModel.canCompile) 1f else 0.38f

    Column {
        // Three equal columns, like a book row, so they line up under the Chapter / Progress header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChapterClick() }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.main_chapter_label, uiModel.chapter.title),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (hasAnyTake) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
                if (heldBack) {
                    Text(
                        text = stringResource(Res.string.label_earlier_structure),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                ProgressBar(uiModel.completion)
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Compile/layers icon. Click semantics depend on chapter state —
                // see onCompileClick wiring in ChapterListContent.
                if (isCompiling) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onCompileClick, enabled = uiModel.canCompile) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = when {
                                uiModel.canCompile -> stringResource(Res.string.cd_compile_chapter)
                                uiModel.hasChapterTake -> stringResource(Res.string.cd_chapter_compiled)
                                else -> stringResource(Res.string.cd_compile_chapter_not_ready)
                            },
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = layersAlpha),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Record button — always shown
                IconButton(onClick = onRecordChapter) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = stringResource(Res.string.cd_record_chapter),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            IconButton(onClick = onExpandToggle, enabled = uiModel.takes.isNotEmpty()) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) stringResource(Res.string.action_collapse) else stringResource(Res.string.cd_expand_chapter_take),
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Expanded body — playback row for the compiled chapter take. Visible
        // only when the user toggled it open and a chapter take exists.
        if (isExpanded && shownTake != null) {
            ChapterTakePlaybackRow(
                take = shownTake,
                position = uiModel.takes.indexOfFirst { it.number == shownTake.number } + 1,
                takeCount = uiModel.takes.size,
                isSelected = shownTake.number == uiModel.chapterTakeNumber,
                onPrevious = { onShowOtherTake(-1) },
                onNext = { onShowOtherTake(1) },
                onSelect = onSelectTake,
                isPlaying = isPlaying,
                playbackProgress = playbackProgress,
                elapsedText = elapsedText,
                durationText = durationText,
                onPlayPause = onPlayPause,
                onDelete = onDelete,
                onOpenPlayback = onOpenPlayback
            )
        }
    }
}

/** A chapter take's player, laid out like a verse take's: transport, actions, then which take. */
@Composable
private fun ChapterTakePlaybackRow(
    take: Take,
    position: Int,
    takeCount: Int,
    isSelected: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: () -> Unit,
    isPlaying: Boolean,
    playbackProgress: Float,
    elapsedText: String,
    durationText: String,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
    onOpenPlayback: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(Res.string.action_pause) else stringResource(Res.string.action_play),
                    modifier = Modifier.size(32.dp)
                )
            }
            ProgressBar(playbackProgress, modifier = Modifier.weight(1f))
            // One time, as in the design: how long the take is, then how far in once it has started.
            Text(
                text = if (playbackProgress > 0f) elapsedText else durationText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.widthIn(max = 404.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.cd_delete_chapter_take),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onOpenPlayback) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = stringResource(Res.string.cd_open_in_playback),
                    modifier = Modifier.size(36.dp)
                )
            }
            // Filled when this is the chapter's selected take; tapping an outline star selects it.
            IconButton(onClick = onSelect, enabled = !isSelected) {
                Icon(
                    if (isSelected) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(Res.string.cd_select_take),
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, enabled = position > 1) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.cd_previous_take))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(Res.string.unit_take_position, position, takeCount),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    takeDateText(take.createdTimestamp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onNext, enabled = position < takeCount) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(Res.string.cd_next_take))
            }
        }
    }
}

@Preview
@Composable
fun ChapterItemPreview() {
    Column {
        ChapterItem(
            uiModel = ChapterUiModel(
                chapter = MockData.createMockChapter(1, "Chapter 1", "1"),
                hasContent = true,
                progress = 0.08f,
                hasChapterTake = false,
                canCompile = false
            ),
            isExpanded = false,
            isCompiling = false,
            isPlaying = false,
            playbackProgress = 0f,
            elapsedText = "00:00:00",
            durationText = "00:00:00",
            onChapterClick = {},
            onRecordChapter = {},
            onCompileClick = {},
            onExpandToggle = {},
            onPlayPause = {},
            onDelete = {}
        )
        HorizontalDivider()
        ChapterItem(
            uiModel = ChapterUiModel(
                chapter = MockData.createMockChapter(2, "Chapter 2", "2"),
                hasContent = false,
                progress = 0f,
                hasChapterTake = false,
                canCompile = false
            ),
            isExpanded = false,
            isCompiling = false,
            isPlaying = false,
            playbackProgress = 0f,
            elapsedText = "00:00:00",
            durationText = "00:00:00",
            onChapterClick = {},
            onRecordChapter = {},
            onCompileClick = {},
            onExpandToggle = {},
            onPlayPause = {},
            onDelete = {}
        )
    }
}

@Preview
@Composable
fun ChapterListContentPreview() {
    ChapterListContent(
        uiState = ChapterListUiState(
            chapters = listOf(
                ChapterUiModel(MockData.createMockChapter(1, "Chapter 1", "1"), hasContent = true, progress = 0.08f),
                ChapterUiModel(MockData.createMockChapter(2, "Chapter 2", "2"), hasContent = false, progress = 0f),
                ChapterUiModel(MockData.createMockChapter(3, "Chapter 3", "3"), hasContent = false, progress = 0f)
            ),
            workbook = null
        ),
        onBackClick = {},
        onChapterClick = {},
        onRecordChapter = {}
    )
}
