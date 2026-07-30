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
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.ChapterTranslationBuilder
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
    /** True when [ChapterTranslationBuilder] has produced a compiled chapter take. */
    val hasChapterTake: Boolean = false,
    /** The selected chapter take's number, when one exists (for opening in playback). */
    val chapterTakeNumber: Int? = null,
    /** True only when every verse has a selected take, i.e., compile is allowed. */
    val canCompile: Boolean = false
)

data class ChapterListUiState(
    val isLoading: Boolean = false,
    val chapters: List<ChapterUiModel> = emptyList(),
    val workbook: Workbook? = null,
    val error: String? = null,

    // Compile flow state — keyed by chapter.sort.
    val compilingChapterSort: Int? = null,

    // Expanded-card playback. Only one chapter take can be loaded at a time,
    // just like the original BTT-Recorder. `loadedChapterSort` tracks which
    // take is currently in the player (so duration/elapsed survive a pause),
    // while `isChapterPlaying` is the live play/pause status of *that* take.
    val loadedChapterSort: Int? = null,
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
                        launch {
                            chapter.audio.selectedFlow.collect { holder ->
                                // A deleted take is sometimes still referenced by the
                                // selectedFlow holder (the relay doesn't auto-clear on
                                // delete), so treat a tombstoned take as "no chapter take"
                                // for UI purposes.
                                val take = holder.value
                                val hasChapterTake = take != null && !take.isDeleted()
                                updateChapterRow(chapter) { existing ->
                                    existing.copy(
                                        hasChapterTake = hasChapterTake,
                                        chapterTakeNumber = if (hasChapterTake) take?.number else null
                                    )
                                }
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
     * Idempotently loads the chapter's selected take into the player so duration
     * shows up the moment the user expands the row — without starting playback.
     * Safe to call multiple times; loading the same take twice is a no-op.
     */
    fun prepareChapterPlayback(chapter: Chapter) {
        val take = chapter.audio.getSelectedTake() ?: return
        if (_uiState.value.loadedChapterSort == chapter.sort) return
        launchLogged(Dispatchers.IO) {
            try {
                // If a different chapter is currently in the player, stop it first
                // so its duration/elapsed don't bleed into the new row.
                if (runCatching { audioPlayer.isPlaying() }.getOrDefault(false)) {
                    runCatching { audioPlayer.pause() }
                }
                stopProgressTicker()

                val reader = OratureAudioFile(take.file).reader()
                audioPlayer.load(reader)
                val durationMs = runCatching { audioPlayer.getDurationMs() }.getOrDefault(0)
                _uiState.update {
                    it.copy(
                        loadedChapterSort = chapter.sort,
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
        val take = chapter.audio.getSelectedTake() ?: return
        launchLogged(Dispatchers.IO) {
            try {
                val state = _uiState.value
                // Ensure the player is holding *this* chapter's take. If it is
                // already loaded we just toggle; otherwise we load before play.
                if (state.loadedChapterSort != chapter.sort) {
                    val reader = OratureAudioFile(take.file).reader()
                    audioPlayer.load(reader)
                    val durationMs = runCatching { audioPlayer.getDurationMs() }.getOrDefault(0)
                    _uiState.update {
                        it.copy(
                            loadedChapterSort = chapter.sort,
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

    fun deleteChapterTake(chapter: Chapter) {
        val take = chapter.audio.getSelectedTake() ?: return
        // Stop playback if this take is what's currently loaded.
        if (_uiState.value.loadedChapterSort == chapter.sort) {
            runCatching { audioPlayer.pause() }
            stopProgressTicker()
            _uiState.update {
                it.copy(
                    loadedChapterSort = null,
                    isChapterPlaying = false,
                    playbackProgress = 0f,
                    elapsedText = "00:00:00",
                    durationText = "00:00:00"
                )
            }
        }
        take.deletedTimestamp.accept(DateHolder.now())
        // selectedFlow doesn't always fire on take deletion (the selected
        // reference itself doesn't change). Update the row directly so the
        // layers icon switches back from "view chapter take" to "ready to
        // compile" without requiring an external refresh.
        updateChapterRow(chapter) { it.copy(hasChapterTake = false, chapterTakeNumber = null) }
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
