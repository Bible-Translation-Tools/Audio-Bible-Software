package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bibletranslationtools.bttrecorder2.services.ProjectSession
import org.bibletranslationtools.shared.preferences.IAppPreferences
import org.bibletranslationtools.shared.ui.failureMessage
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.workbook.AudioState
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.IAudioPlayer
import org.bibletranslationtools.otter.common.data.workbook.SaveResult
import org.bibletranslationtools.otter.common.data.workbook.Take
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.err_no_active_chapter
import org.bibletranslationtools.shared.resources.err_project_not_found
import org.bibletranslationtools.shared.resources.err_chapter_not_found
import org.bibletranslationtools.shared.resources.err_unknown
import org.bibletranslationtools.shared.resources.err_play_audio

data class UnitUiModel(
    val unit: Chunk,
    val hasContent: Boolean = false,
    val takes: Int = 0,
    /**
     * The unit's currently selected take. Kept in UI state (rather than read straight off
     * `unit.audio.getSelectedTake()` while composing) because that value is invisible to
     * Compose — the checkmark never repainted after selecting a take.
     */
    val selectedTake: Take? = null
)

data class UnitListUiState(
    val isLoading: Boolean = false,
    val units: List<UnitUiModel> = emptyList(),
    val chapter: Chapter? = null,
    val workbook: Workbook? = null,
    val error: String? = null,
    /** A change the user made that was not saved; shown once, then cleared with [UnitListViewModel.dismissSaveError]. */
    val saveError: String? = null,
    val isPlaying: Boolean = false,
    val playbackProgress: Float = 0f,
    val currentPlayingTake: Take? = null,
    // key = unit.sort, value = index into sorted non-deleted takes list being browsed
    val currentTakeIndices: Map<Int, Int> = emptyMap(),
    val elapsedText: String = "00:00:00",
    val durationText: String = "00:00:00",
    // Pre-computed durations; key = take file absolutePath, value = "HH:MM:SS"
    val takeDurations: Map<String, String> = emptyMap()
)

class UnitListViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel(), KoinComponent {

    private val projectSession: ProjectSession by inject()
    private val appPreferences: IAppPreferences by inject()

    private val _uiState = MutableStateFlow(UnitListUiState())
    val uiState: StateFlow<UnitListUiState> = _uiState.asStateFlow()

    private val audioConnectionFactory: AudioPlayerConnectionFactory by inject()
    private var audioPlayer: IAudioPlayer? = null

    private val playerId = kotlin.random.Random.nextInt()
    private var playbackJob: Job? = null
    private var loadingJob: Job? = null

    init {
        audioPlayer = AudioPlayerConnection(
            id = playerId,
            factory = audioConnectionFactory,
            scope = viewModelScope,
            controlDispatcher = Dispatchers.Default
        )

        // We deliberately do NOT drive isPlaying from the events flow.
        //
        //   1. `play()` is fire-and-forget on a control dispatcher, so a Pause
        //      event from the global worker (e.g., from the recorder or chapter
        //      player taking over) can fire after we've called play() but
        //      before our connection becomes active — that would flip the UI
        //      back to "paused" with the position reset.
        //   2. `pause()` does NOT emit a Stop, but `load()` may; treating Stop
        //      as "the user paused" reset our position state to zero.
        //
        // Instead, isPlaying is owned by user intent (set in playTake) and the
        // ticker; events are only used to surface errors.
        launchLogged {
            audioPlayer?.events?.collect { event ->
                if (event is AudioPlayerEvent.Error) {
                    _uiState.update { it.copy(error = event.message) }
                }
            }
        }
    }

    /**
     * Polls position + duration at 100 ms intervals and pushes them into UI
     * state. End-of-playback (position reaches duration) resets the slider and
     * flips isPlaying back to false. The ticker is otherwise only stopped by
     * an explicit user pause via [playTake].
     */
    private fun startProgressTicker() {
        if (playbackJob?.isActive == true) return
        playbackJob = launchLogged {
            while (isActive) {
                delay(100)
                val durationMs = audioPlayer?.getDurationMs() ?: 0
                val positionMs = audioPlayer?.getLocationMs() ?: 0
                val progress = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f

                if (durationMs > 0 && positionMs >= durationMs) {
                    _uiState.update {
                        it.copy(
                            isPlaying = false,
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
            playbackJob = null
        }
    }

    private fun stopProgressTicker() {
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    fun loadUnits() {
        // Load once per ViewModel. The chunk flow below keeps collecting for the
        // ViewModel's lifetime (the back-stack entry), so the verse rows stay current
        // even while recording. Skipping the reload on re-entry avoids a spinner flash
        // and a leaked duplicate collection, and keeps the list populated so the
        // LazyColumn restores its scroll position (e.g. after recording a verse).
        if (loadingJob?.isActive == true) return
        loadingJob = launchLogged(ioDispatcher) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val nav = appPreferences.navState.first()
                if (!nav.hasActiveChapter) {
                    _uiState.update { it.copy(isLoading = false, error = getString(Res.string.err_no_active_chapter)) }
                    return@launchLogged
                }

                val workbook = projectSession.open(nav.workbookSourceId, nav.workbookTargetId)
                if (workbook == null) {
                    _uiState.update { it.copy(isLoading = false, error = getString(Res.string.err_project_not_found)) }
                    return@launchLogged
                }

                val chapter = workbook.target.chapters().firstOrNull { it.sort == nav.chapterSort }
                if (chapter == null) {
                    _uiState.update { it.copy(isLoading = false, error = getString(Res.string.err_chapter_not_found), workbook = workbook) }
                    return@launchLogged
                }

                _uiState.update { it.copy(workbook = workbook, chapter = chapter) }
                chapter.loadedState()

                // Verses only: the chapter's compiled take lives on `chapter.audio` (see
                // ChapterTranslationBuilder) and the chapter list owns its UI; this screen is
                // exclusively for per-verse takes.
                verseAudio(chapter).collect { verses ->
                    val units = verses.map { (chunk, audio) ->
                        val selected = audio.selectedTake?.takeUnless { it.isDeleted() }
                        UnitUiModel(
                            unit = chunk,
                            hasContent = selected != null,
                            takes = audio.takes.count { !it.isDeleted() },
                            selectedTake = selected
                        )
                    }

                    // Browse indices point at each unit's selected take at first; an index the user
                    // has already set is kept, clamped to the takes there are now (one may have
                    // just been deleted).
                    val existing = _uiState.value.currentTakeIndices
                    val indices = units.associate { unit ->
                        val takes = unit.unit.audio.getAllTakes()
                            .filter { !it.isDeleted() }
                            .sortedBy { it.number }
                        val initial = unit.selectedTake?.let { takes.indexOf(it) }?.coerceAtLeast(0) ?: 0
                        val index = existing[unit.unit.sort] ?: initial
                        unit.unit.sort to index.coerceIn(0, (unit.takes - 1).coerceAtLeast(0))
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            units = units,
                            currentTakeIndices = indices
                        )
                    }

                    // Compute durations for any takes not yet in the cache.
                    launch(ioDispatcher) {
                        val existing = _uiState.value.takeDurations
                        val newDurations = mutableMapOf<String, String>()
                        verses.forEach { (chunk, _) ->
                            chunk.audio.getAllTakes().filter { !it.isDeleted() }.forEach { take ->
                                val key = take.file.absolutePath
                                if (!existing.containsKey(key)) {
                                    try {
                                        val af = OratureAudioFile(take.file)
                                        val ms = (af.totalFrames.toLong() * 1000L / af.sampleRate).toInt()
                                        newDurations[key] = formatTime(ms)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        if (newDurations.isNotEmpty()) {
                            _uiState.update { it.copy(takeDurations = it.takeDurations + newDurations) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading the unit list", e)
                _uiState.update { it.copy(isLoading = false, error = e.message ?: getString(Res.string.err_unknown)) }
            }
        }
    }

    /**
     * Each verse of [chapter]'s active chunk layout with its saved takes, following a new layout
     * and every verse's takes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun verseAudio(chapter: Chapter): Flow<List<Pair<Chunk, AudioState>>> =
        chapter.state.flatMapLatest { state ->
            val verses = state.chunks.filter { it.contentType == ContentType.TEXT }
            if (verses.isEmpty()) {
                flowOf(emptyList<Pair<Chunk, AudioState>>())
            } else {
                combine(verses.map { verse -> verse.audio.state.map { verse to it } }) { it.toList() }
            }
        }

    fun dismissSaveError() {
        _uiState.update { it.copy(saveError = null) }
    }

    private suspend fun showIfFailed(result: SaveResult<*>) {
        result.failureMessage()?.let { message -> _uiState.update { it.copy(saveError = message) } }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer?.release()
        stopProgressTicker()
    }

    // Returns the take the user is currently browsing for a unit (may differ from selected take).
    private fun getCurrentViewedTake(unit: Chunk): Take? {
        val takes = unit.audio.getAllTakes()
            .filter { !it.isDeleted() }
            .sortedBy { it.number }
        val index = _uiState.value.currentTakeIndices[unit.sort] ?: 0
        return takes.getOrNull(index)
    }

    fun togglePlay(unit: Chunk) {
        val take = getCurrentViewedTake(unit) ?: return
        playTake(take)
    }

    fun playTake(take: Take) {
        launchLogged(ioDispatcher) {
            try {
                val state = _uiState.value
                val isSameTakeLoaded = state.currentPlayingTake?.file?.absolutePath ==
                    take.file.absolutePath

                if (isSameTakeLoaded) {
                    // The take is already in the player. Toggle without reloading
                    // — reloading would reset the worker's position to 0, which is
                    // why pressing pause used to "snap back" the slider.
                    if (state.isPlaying) {
                        audioPlayer?.pause()
                        _uiState.update { it.copy(isPlaying = false) }
                        stopProgressTicker()
                    } else {
                        audioPlayer?.play()
                        _uiState.update { it.copy(isPlaying = true) }
                        startProgressTicker()
                    }
                } else {
                    // Switching to a different take. If something else is playing,
                    // pause first so the previous ticker stops cleanly.
                    if (state.isPlaying) {
                        audioPlayer?.pause()
                        stopProgressTicker()
                    }

                    val reader = OratureAudioFile(take.file).reader()
                    audioPlayer?.load(reader)
                    val durationMs = audioPlayer?.getDurationMs() ?: 0
                    audioPlayer?.play()
                    _uiState.update {
                        it.copy(
                            currentPlayingTake = take,
                            isPlaying = true,
                            playbackProgress = 0f,
                            elapsedText = "00:00:00",
                            durationText = formatTime(durationMs)
                        )
                    }
                    startProgressTicker()
                }
            } catch (e: Exception) {
                logFailure("playing the take", e)
                _uiState.update { it.copy(error = getString(Res.string.err_play_audio, e.message ?: "")) }
            }
        }
    }

    // Cycles through takes without changing the officially selected take.
    fun cycleTake(unit: Chunk, direction: Int) {
        val takes = unit.audio.getAllTakes()
            .filter { !it.isDeleted() }
            .sortedBy { it.number }
        if (takes.isEmpty()) return

        val currentIndex = _uiState.value.currentTakeIndices[unit.sort] ?: 0
        val newIndex = (currentIndex + direction + takes.size) % takes.size
        _uiState.update { state ->
            state.copy(currentTakeIndices = state.currentTakeIndices + (unit.sort to newIndex))
        }
    }

    // Explicitly marks the currently browsed take as the selected take.
    fun selectCurrentTake(unit: Chunk) {
        val take = getCurrentViewedTake(unit) ?: return
        // Saved first; the card's checkmark follows the unit's saved state through loadUnits.
        launchLogged { showIfFailed(unit.audio.selectTake(take)) }
    }

    fun deleteTake(unit: Chunk, take: Take) {
        // Stop playback if the deleted take is the one currently loaded.
        val playingSame = _uiState.value.currentPlayingTake?.file?.absolutePath ==
            take.file.absolutePath
        if (playingSame) {
            runCatching { audioPlayer?.pause() }
            stopProgressTicker()
            _uiState.update { state ->
                state.copy(
                    isPlaying = false,
                    currentPlayingTake = null,
                    playbackProgress = 0f,
                    elapsedText = "00:00:00",
                    durationText = "00:00:00"
                )
            }
        }
        // Saved first. Deleting the selected take also deselects it, in the same step; the row and
        // its browse index follow the unit's saved state through loadUnits.
        launchLogged { showIfFailed(unit.audio.deleteTake(take)) }
    }
}
