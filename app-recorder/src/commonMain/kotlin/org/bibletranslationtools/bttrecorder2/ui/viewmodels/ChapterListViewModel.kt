package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.otter.common.api.persistence.repositories.ICollectionRepository
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.DateHolder
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.ChapterTranslationBuilder
import org.bibletranslationtools.otter.common.domain.collections.BookEditionState
import org.bibletranslationtools.otter.common.domain.collections.UpgradeBookEdition
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.err_no_active_project
import org.bibletranslationtools.shared.resources.err_project_not_found
import org.bibletranslationtools.shared.resources.err_workbook_not_found
import org.bibletranslationtools.shared.resources.err_unknown
import org.bibletranslationtools.shared.resources.err_compile_failed
import org.bibletranslationtools.shared.resources.err_load_chapter_audio
import org.bibletranslationtools.shared.resources.err_play_chapter_audio
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class ChapterUiModel(
    val chapter: Chapter,
    /** True when any verse in the chapter has a selected take. */
    val hasContent: Boolean = false,
    /** Fraction of verses with a selected take (0f..1f). */
    val progress: Float = 0f,
    /**
     * The chapter's takes, oldest first, deleted ones left out: compiled here, recorded as a whole,
     * or brought in by an import (a migrated book can have one per version label it was recorded
     * under).
     */
    val takes: List<Take> = emptyList(),
    /**
     * The chapter's selected take. Kept in UI state (not read off `chapter.audio` while composing)
     * so the select checkmark repaints when it changes.
     */
    val selectedTake: Take? = null,
    /** True only when every verse has a selected take, i.e., compile is allowed. */
    val canCompile: Boolean = false
) {
    /** The chapter has a take to play, select or delete; the card can expand. */
    val hasChapterTake: Boolean get() = takes.isNotEmpty()
}

data class ChapterListUiState(
    val isLoading: Boolean = false,
    val chapters: List<ChapterUiModel> = emptyList(),
    val workbook: Workbook? = null,
    val error: String? = null,
    /** The book's source edition, the editions it can move to, and its held-back chapters. */
    val editionState: BookEditionState? = null,

    // Compile flow state — keyed by chapter.sort.
    val compilingChapterSort: Int? = null,

    /**
     * The take each chapter's card is showing, as an index into its [ChapterUiModel.takes]; keyed
     * by chapter sort. Browsing doesn't change which take is selected.
     */
    val currentTakeIndices: Map<Int, Int> = emptyMap(),

    // Expanded-card playback. Only one chapter take can be loaded at a time,
    // just like the original BTT-Recorder. `loadedTakePath` is the take
    // currently in the player (so duration/elapsed survive a pause), while
    // `isChapterPlaying` is the live play/pause status of *that* take.
    val loadedTakePath: String? = null,
    val isChapterPlaying: Boolean = false,
    val playbackProgress: Float = 0f,
    val elapsedText: String = "00:00:00",
    val durationText: String = "00:00:00"
)

class ChapterListViewModel : ViewModel(), KoinComponent {

    private val workbookRepository: IWorkbookRepository by inject()
    private val collectionRepository: ICollectionRepository by inject()
    private val appPreferences: IAppPreferences by inject()
    private val audioConnectionFactory: AudioPlayerConnectionFactory by inject()
    private val chapterTranslationBuilder: ChapterTranslationBuilder by inject()
    private val upgradeBookEdition: UpgradeBookEdition by inject()

    private val _uiState = MutableStateFlow(ChapterListUiState())
    val uiState: StateFlow<ChapterListUiState> = _uiState.asStateFlow()

    private var loadingJob: Job? = null

    // Shared chapter-take player used by the expanded-card playback row.
    //
    // We deliberately don't drive the UI off `player.events` here. The
    // `AudioPlayerConnection` worker is global — events fire across every
    // connection on the same hardware sink, so the UnitList player and the
    // recorder can produce events that race with our load → play sequence
    // (e.g., a Stop event after we set `isChapterPlaying = true`). The polling
    // ticker below filters by ID via `player.isPlaying()` and only reports
    // true when *this* connection owns the hardware. Errors still come through
    // the events flow because they're rare and worth surfacing.
    private val playerId = kotlin.random.Random.nextInt()
    private val audioPlayer: IAudioPlayer by lazy {
        AudioPlayerConnection(
            id = playerId,
            factory = audioConnectionFactory,
            scope = viewModelScope,
            controlDispatcher = Dispatchers.Default
        ).also { player ->
            launchLogged {
                player.events.collect { event ->
                    if (event is AudioPlayerEvent.Error) {
                        _uiState.update { it.copy(error = event.message) }
                    }
                }
            }
        }
    }
    private var tickerJob: Job? = null

    fun loadChapters() {
        // Load once per ViewModel. The chunk-LIST flow below only re-emits when the
        // set of chunks changes, not when a verse's take changes, so instead of a
        // full reload on re-entry (which would reset scroll) we refresh each row's
        // verse-level progress in place — this picks up takes recorded while the user
        // was in the verse list without clearing the list.
        if (loadingJob?.isActive == true) {
            refreshProgress()
            return
        }
        loadingJob = launchLogged(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, chapters = emptyList()) }
            try {
                val nav = appPreferences.navState.first()
                if (!nav.hasActiveWorkbook) {
                    _uiState.update { it.copy(isLoading = false, error = getString(Res.string.err_no_active_project)) }
                    return@launchLogged
                }

                val sourceC = collectionRepository.getProjectSuspend(nav.workbookSourceId)
                val targetC = collectionRepository.getProjectSuspend(nav.workbookTargetId)

                if (sourceC == null || targetC == null) {
                    _uiState.update { it.copy(isLoading = false, error = getString(Res.string.err_project_not_found)) }
                    return@launchLogged
                }

                val workbook = workbookRepository.get(sourceC, targetC)
                if (workbook == null) {
                    _uiState.update { it.copy(isLoading = false, error = getString(Res.string.err_workbook_not_found)) }
                    return@launchLogged
                }

                _uiState.update { it.copy(workbook = workbook) }
                launchLogged(Dispatchers.IO) {
                    val editions = runCatching { upgradeBookEdition.editionState(targetC.id) }
                        .onFailure { logFailure("loading the book's editions", it) }
                        .getOrNull()
                    _uiState.update { it.copy(editionState = editions) }
                }

                coroutineScope {
                    workbook.target.chaptersFlow.collect { chapter ->
                        // Observe two streams per chapter:
                        //   1. The chunks flow — drives verse-level progress + canCompile.
                        //   2. The chapter's own selected-take flow — drives hasChapterTake.
                        // Both update the same `ChapterUiModel` row, so we merge by always
                        // recomputing from the latest known values.
                        launch {
                            chapter.observableFlowChunks.collect { allChunks ->
                                // Progress + compile-readiness are about verses only.
                                // The chapter-meta chunk would otherwise inflate the
                                // denominator and break "every verse recorded" logic.
                                val verses = allChunks.filter { it.contentType == ContentType.TEXT }
                                val total = verses.size
                                val started = verses.count { it.hasSelectedAudio() }
                                val progress = if (total > 0) started.toFloat() / total else 0f
                                val hasContent = started > 0
                                val canCompile = total > 0 && started == total
                                updateChapterRow(chapter) { existing ->
                                    existing.copy(
                                        hasContent = hasContent,
                                        progress = progress,
                                        canCompile = canCompile
                                    )
                                }
                            }
                        }
                        // The chapter's takes: a new one (compiled, recorded, imported) or a
                        // change of selection both refresh the row's take list. A new selection
                        // (a compile or a recording selects its take) moves the card to it.
                        launch { chapter.audio.takesFlow.collect { refreshChapterTakes(chapter) } }
                        launch {
                            chapter.audio.selectedFlow.collect {
                                refreshChapterTakes(chapter, showSelected = true)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading the chapter list", e)
                _uiState.update { it.copy(isLoading = false, error = e.message ?: getString(Res.string.err_unknown)) }
            }
        }
    }

    /**
     * Loads the list again from scratch, for after the book moved to another edition: its
     * source, and the verses of the chapters that took on the new structure, are different.
     */
    fun reload() {
        if (_uiState.value.loadedTakePath != null) runCatching { audioPlayer.pause() }
        stopProgressTicker()
        loadingJob?.cancel()
        loadingJob = null
        _uiState.update { ChapterListUiState() }
        loadChapters()
    }

    /**
     * Recomputes each existing chapter row's verse-level state (hasContent,
     * progress, canCompile) from the current chunk selections, updating rows in
     * place (no list clear, so the LazyColumn scroll position is preserved). Called
     * on screen re-entry to reflect verse takes recorded while away, which the
     * chunk-list flow doesn't surface on its own.
     */
    private fun refreshProgress() {
        val rows = _uiState.value.chapters
        if (rows.isEmpty()) return
        launchLogged(Dispatchers.IO) {
            rows.forEach { row ->
                val verses = row.chapter.chunksSuspend().filter { it.contentType == ContentType.TEXT }
                val total = verses.size
                val started = verses.count { it.hasSelectedAudio() }
                updateChapterRow(row.chapter) { existing ->
                    existing.copy(
                        hasContent = started > 0,
                        progress = if (total > 0) started.toFloat() / total else 0f,
                        canCompile = total > 0 && started == total
                    )
                }
            }
        }
    }

    /**
     * Re-reads [chapter]'s takes and selection into its row. The card keeps showing the take at
     * the position it was showing, unless [showSelected]; one it hasn't shown yet starts at the
     * selected take.
     */
    private fun refreshChapterTakes(chapter: Chapter, showSelected: Boolean = false) {
        val takes = chapter.audio.getAllTakes().filter { !it.isDeleted() }.sortedBy { it.number }
        // A deleted take can still be held as selected (the relay doesn't clear on delete).
        val selected = chapter.audio.getSelectedTake()?.takeIf { !it.isDeleted() }
        updateChapterRow(chapter) { it.copy(takes = takes, selectedTake = selected) }
        _uiState.update { state ->
            val selectedIndex = selected?.let { takes.indexOf(it) }?.takeIf { it >= 0 }
            val index = (if (showSelected) selectedIndex else null)
                ?: state.currentTakeIndices[chapter.sort]
                ?: selectedIndex
                ?: 0
            state.copy(
                currentTakeIndices = state.currentTakeIndices +
                    (chapter.sort to index.coerceIn(0, (takes.size - 1).coerceAtLeast(0)))
            )
        }
    }

    /** The take [chapter]'s card is showing, or null when it has none. */
    private fun viewedTake(chapter: Chapter): Take? {
        val state = _uiState.value
        val row = state.chapters.firstOrNull { it.chapter.sort == chapter.sort } ?: return null
        return row.takes.getOrNull(state.currentTakeIndices[chapter.sort] ?: 0)
    }

    /** Shows the next ([direction] 1) or previous (-1) of [chapter]'s takes, without selecting it. */
    fun cycleChapterTake(chapter: Chapter, direction: Int) {
        val takes = _uiState.value.chapters.firstOrNull { it.chapter.sort == chapter.sort }?.takes.orEmpty()
        if (takes.isEmpty()) return
        val current = _uiState.value.currentTakeIndices[chapter.sort] ?: 0
        val next = (current + direction + takes.size) % takes.size
        _uiState.update { it.copy(currentTakeIndices = it.currentTakeIndices + (chapter.sort to next)) }
        // Load it, so its duration shows straight away, as on expanding.
        prepareChapterPlayback(chapter)
    }

    /** Makes the take [chapter]'s card is showing its selected take. */
    fun selectChapterTake(chapter: Chapter) {
        val take = viewedTake(chapter) ?: return
        // Persisted by the workbook's selection relay; selectedFlow then refreshes the row.
        chapter.audio.selectTake(take)
        refreshChapterTakes(chapter)
    }

    /**
     * Merges a single chapter's UI model into the sorted chapter list, creating
     * it if it doesn't exist yet. Centralized so the two per-chapter streams
     * (chunks + chapter audio) don't fight over the list shape.
     */
    private fun updateChapterRow(
        chapter: Chapter,
        mutate: (ChapterUiModel) -> ChapterUiModel
    ) {
        _uiState.update { state ->
            val list = state.chapters.toMutableList()
            val idx = list.indexOfFirst { it.chapter.sort == chapter.sort }
            if (idx >= 0) {
                list[idx] = mutate(list[idx])
            } else {
                list.add(mutate(ChapterUiModel(chapter)))
            }
            list.sortBy { it.chapter.sort }
            state.copy(isLoading = false, chapters = list)
        }
    }

    // -------------------------------------------------------------------------
    // Compile
    // -------------------------------------------------------------------------

    /**
     * Concatenates every verse's selected take into a single chapter-level take
     * via [ChapterTranslationBuilder.getOrCompile]. Refuses to run when the
     * chapter isn't compile-ready (i.e., not every verse has a selected take);
     * the UI's "Compile" button should already be disabled in that case but we
     * defend in depth here.
     */
    fun compileChapter(chapter: Chapter) {
        val state = _uiState.value
        val workbook = state.workbook ?: return
        val row = state.chapters.firstOrNull { it.chapter.sort == chapter.sort } ?: return
        if (!row.canCompile || state.compilingChapterSort != null) return

        _uiState.update { it.copy(compilingChapterSort = chapter.sort, error = null) }
        launchLogged {
            try {
                withContext(Dispatchers.IO) {
                    // getOrCompile inserts the compiled take, and inserting AUTO-selects
                    // it — but only inside the insert's success callback, after the DB
                    // has assigned the take's id and committed the row (see
                    // WorkbookRepository.constructAssociatedAudio). Do NOT selectTake here:
                    // an extra select races the async insert and points the chapter
                    // content's selected_take_fk at an uncommitted take id, which fails the
                    // SQLite foreign-key constraint. The auto-select drives selectedFlow.
                    chapterTranslationBuilder.getOrCompile(workbook, chapter).await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("compiling the chapter", e)
                _uiState.update { it.copy(error = getString(Res.string.err_compile_failed, e.message ?: getString(Res.string.err_unknown))) }
            } finally {
                _uiState.update { it.copy(compilingChapterSort = null) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Chapter-take playback (expanded card)
    // -------------------------------------------------------------------------

    /**
     * Idempotently loads the take the chapter's card is showing into the player so
     * duration shows up the moment the user expands the row (or steps to another
     * take) — without starting playback. Loading the same take twice is a no-op.
     */
    fun prepareChapterPlayback(chapter: Chapter) {
        val take = viewedTake(chapter) ?: return
        if (_uiState.value.loadedTakePath == take.file.absolutePath) return
        launchLogged(Dispatchers.IO) {
            try {
                // If a different take is currently in the player, stop it first
                // so its duration/elapsed don't bleed into this one.
                if (runCatching { audioPlayer.isPlaying() }.getOrDefault(false)) {
                    runCatching { audioPlayer.pause() }
                }
                stopProgressTicker()

                val reader = OratureAudioFile(take.file).reader()
                audioPlayer.load(reader)
                val durationMs = runCatching { audioPlayer.getDurationMs() }.getOrDefault(0)
                _uiState.update {
                    it.copy(
                        loadedTakePath = take.file.absolutePath,
                        isChapterPlaying = false,
                        playbackProgress = 0f,
                        elapsedText = "00:00:00",
                        durationText = formatTime(durationMs)
                    )
                }
            } catch (e: Exception) {
                logFailure("preparing chapter playback", e)
                _uiState.update { it.copy(error = getString(Res.string.err_load_chapter_audio, e.message ?: "")) }
            }
        }
    }

    fun toggleChapterPlayback(chapter: Chapter) {
        val take = viewedTake(chapter) ?: return
        launchLogged(Dispatchers.IO) {
            try {
                val state = _uiState.value
                // Ensure the player is holding the take this card shows. If it is
                // already loaded we just toggle; otherwise we load before play.
                if (state.loadedTakePath != take.file.absolutePath) {
                    if (state.isChapterPlaying) {
                        runCatching { audioPlayer.pause() }
                        stopProgressTicker()
                        _uiState.update { it.copy(isChapterPlaying = false) }
                    }
                    val reader = OratureAudioFile(take.file).reader()
                    audioPlayer.load(reader)
                    val durationMs = runCatching { audioPlayer.getDurationMs() }.getOrDefault(0)
                    _uiState.update {
                        it.copy(
                            loadedTakePath = take.file.absolutePath,
                            playbackProgress = 0f,
                            elapsedText = "00:00:00",
                            durationText = formatTime(durationMs)
                        )
                    }
                }

                if (_uiState.value.isChapterPlaying) {
                    audioPlayer.pause()
                    _uiState.update { it.copy(isChapterPlaying = false) }
                    stopProgressTicker()
                } else {
                    audioPlayer.play()
                    _uiState.update { it.copy(isChapterPlaying = true) }
                    startProgressTicker()
                }
            } catch (e: Exception) {
                logFailure("toggling chapter playback", e)
                _uiState.update { it.copy(error = getString(Res.string.err_play_chapter_audio, e.message ?: "")) }
            }
        }
    }

    /** Deletes the take [chapter]'s card is showing; deleting the selected one leaves none selected. */
    fun deleteChapterTake(chapter: Chapter) {
        val take = viewedTake(chapter) ?: return
        // Stop playback if this take is what's currently loaded.
        if (_uiState.value.loadedTakePath == take.file.absolutePath) {
            runCatching { audioPlayer.pause() }
            stopProgressTicker()
            _uiState.update {
                it.copy(
                    loadedTakePath = null,
                    isChapterPlaying = false,
                    playbackProgress = 0f,
                    elapsedText = "00:00:00",
                    durationText = "00:00:00"
                )
            }
        }
        take.deletedTimestamp.accept(DateHolder.now())
        // Neither takesFlow nor selectedFlow fires on a deletion (the take and the selected
        // reference are both unchanged), so refresh the row here: the take leaves the list, and
        // the card stays on the take at the same position, or the new last one.
        refreshChapterTakes(chapter)
    }

    /**
     * Polls position + duration at 100 ms intervals and pushes them into UI
     * state. Critically, we do NOT use [audioPlayer.isPlaying] to gate the
     * ticker — [AudioPlayerConnection.play] dispatches asynchronously on its
     * control dispatcher, so for the first ~tens of milliseconds after play()
     * the active-connection ID isn't us yet and `isPlaying()` returns false.
     * Previously that caused the ticker to break out on its very first
     * iteration and never advance the slider.
     *
     * Instead, `isChapterPlaying` is driven by user intent inside
     * [toggleChapterPlayback]. The ticker's only stop condition is natural
     * end-of-playback (position reaches duration), at which point it resets
     * the slider to zero and flips the play button back.
     */
    private fun startProgressTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = launchLogged {
            while (isActive) {
                delay(100)

                val durationMs = runCatching { audioPlayer.getDurationMs() }.getOrDefault(0)
                val positionMs = runCatching { audioPlayer.getLocationMs() }.getOrDefault(0)
                val progress = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f

                // End-of-playback detection: position has caught up to (or
                // passed) duration. Reset state and exit the ticker.
                if (durationMs > 0 && positionMs >= durationMs) {
                    _uiState.update {
                        it.copy(
                            isChapterPlaying = false,
                            playbackProgress = 0f,
                            elapsedText = "00:00:00",
                            durationText = formatTime(durationMs)
                        )
                    }
                    break
                }

                _uiState.update {
                    it.copy(
                        playbackProgress = progress,
                        elapsedText = formatTime(positionMs),
                        durationText = formatTime(durationMs)
                    )
                }
            }
            tickerJob = null
        }
    }

    private fun stopProgressTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    override fun onCleared() {
        super.onCleared()
        stopProgressTicker()
        runCatching { audioPlayer.release() }
    }
}
