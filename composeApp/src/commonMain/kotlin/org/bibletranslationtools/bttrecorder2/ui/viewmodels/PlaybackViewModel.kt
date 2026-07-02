package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import org.bibletranslationtools.bttrecorder2.ui.playback.CutAwareAudioFileReader
import org.bibletranslationtools.bttrecorder2.ui.playback.MinimapWaveformRenderer
import org.bibletranslationtools.bttrecorder2.ui.playback.PlaybackWaveformRenderer
import org.bibletranslationtools.bttrecorder2.ui.playback.SourceAudioPlayerController
import org.bibletranslationtools.bttrecorder2.ui.playback.WaveEditSession
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.AudioBouncer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.Recordable
import org.bibletranslationtools.otter.common.domain.content.TakeCreator
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.jetbrains.compose.resources.getString
import btt_recorder2.composeapp.generated.resources.Res
import btt_recorder2.composeapp.generated.resources.err_no_edits_to_save
import btt_recorder2.composeapp.generated.resources.err_save_edited_take
import btt_recorder2.composeapp.generated.resources.err_save_verse_markers
import btt_recorder2.composeapp.generated.resources.err_load_take
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class PlaybackViewModel(
    private val workbookRepository: IWorkbookRepository,
    private val audioPlayerFactory: AudioPlayerConnectionFactory,
    private val takeCreator: TakeCreator,
    private val audioBouncer: AudioBouncer
) : ViewModel() {

    data class TargetUiState(
        val languageLabel: String = "",
        val projectLabel: String = "",
        val bookLabel: String = "",
        val chapterValue: String = "",
        val unitValue: String = ""
    )

    data class PlaybackUiState(
        val targetUi: TargetUiState = TargetUiState(),
        val takes: List<Take> = emptyList(),
        val selectedTake: Take? = null,
        // Raw take number; the view formats the localized "Take N" label.
        val currentTakeNumber: Int? = null,
        val isPlaying: Boolean = false,
        val progress: Float = 0f,
        val currentFrame: Int = 0,
        val durationFrames: Int = 0,
        val sampleRate: Int = 44100,
        val elapsedMs: Int = 0,
        val durationMs: Int = 0,
        val elapsedText: String = "00:00:00",
        val durationText: String = "00:00:00",
        val waveformSamples: FloatArray = floatArrayOf(),
        val markerFrames: List<Int> = emptyList(),
        val markerLabels: List<String> = emptyList(),
        val minimapSamples: FloatArray = floatArrayOf(),
        val showMinimap: Boolean = true,
        val sourceAudioAvailable: Boolean = false,
        val selectionStartProgress: Float? = null,
        val selectionEndProgress: Float? = null,
        val canCutSelection: Boolean = false,
        val canUndoEdit: Boolean = false,
        val canRedoEdit: Boolean = false,
        val isVerseMarkerMode: Boolean = false,
        val versesMarked: Int = 0,
        val error: String? = null
    )

    sealed class NavEvent {
        data class Rerecord(
            val sourceId: Int,
            val targetId: Int,
            val chapterNumber: Int,
            val unitNumber: Int
        ) : NavEvent()
        data class Insert(
            val sourceId: Int,
            val targetId: Int,
            val chapterNumber: Int,
            val unitNumber: Int
        ) : NavEvent()

        /** Leave the editor and return to the unit list. */
        object Exit : NavEvent()

        /** Ask the view to confirm leaving while there are unsaved edits. */
        object ConfirmExit : NavEvent()
    }

    private data class PlaybackTarget(
        val chapter: Chapter,
        val chunk: Chunk?
    ) {
        val recordable: Recordable
            get() = chunk ?: chapter
    }

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _editedTakeSavedEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val editedTakeSavedEvents: SharedFlow<Int> = _editedTakeSavedEvents.asSharedFlow()

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    private val playerId = Random.nextInt()
    private val audioPlayer: IAudioPlayer = AudioPlayerConnection(
        id = playerId,
        factory = audioPlayerFactory,
        scope = viewModelScope,
        controlDispatcher = Dispatchers.Default
    )

    private val sourceAudioController = SourceAudioPlayerController(
        factory = audioPlayerFactory,
        scope = viewModelScope
    )

    val sourceAudioState: StateFlow<SourceAudioPlayerController.UiState> = sourceAudioController.uiState

    private var workbook: Workbook? = null
    private var targets: List<PlaybackTarget> = emptyList()
    private var currentTargetIndex = -1
    private var associatedAudio: AssociatedAudio? = null
    private var requestedTakeNumber: Int? = null

    private var takesJob: Job? = null
    private var selectedJob: Job? = null
    private var tickerJob: Job? = null
    private var minimapRenderJob: Job? = null

    // Render requests for the waveform, keyed by desired center frame. A CONFLATED
    // channel keeps latest-wins semantics (intermediate frames dropped while a
    // render is in flight) but, unlike a StateFlow, never dedups equal values — so
    // the initial frame-0 render and same-frame re-renders (e.g. re-centering after
    // a stop at frame 0) always fire. A StateFlow here silently swallowed the first
    // render because refreshWaveform() set it to 0 while it already held 0.
    private val renderRequests = Channel<Int>(Channel.CONFLATED)

    private fun requestRender(frame: Int) {
        renderRequests.trySend(frame)
    }

    private var waveformWidth: Int = 0
    private var minimapWidth: Int = 0
    private var waveformRenderer: PlaybackWaveformRenderer? = null
    private var minimapRenderer: MinimapWaveformRenderer? = null
    private var waveformSampleRate: Int = 44100
    private var markerFrames: List<Int> = emptyList()
    private var markerLabels: List<String> = emptyList()
    private var baseMarkers: List<AudioMarker> = emptyList()
    private var activeTake: Take? = null

    private var editSession: WaveEditSession? = null
    private var selectionStartFrame: Int? = null
    private var selectionEndFrame: Int? = null

    private val droppedVerseMarkerFrames = mutableListOf<Int>()

    // Authoritative, mutable list of verse-marker frame positions for the loaded
    // take. Drag operations mutate this in place; it is mirrored into
    // uiState.markerFrames. Kept separate from `markerFrames` (the last-rendered
    // snapshot) so a drag has a stable index to work against.
    private val editedVerseMarkers = mutableListOf<Int>()
    private var draggingVerseMarkerIndex: Int = -1

    // Live-scrub state for waveform drag. While scrubbing we stop the UI
    // auto-follow ticker (audio keeps playing) and re-render on each move; on
    // release we commit the seek and, if it was playing, resume following.
    private var scrubWasPlaying: Boolean = false

    init {
        // Serialize waveform renders to a single worker. `collectLatest` would
        // not help here: `renderCentered` is non-suspending, so cancellation
        // has no effect and a "cancelled" render still runs to completion on
        // its worker while the new one starts on a different worker, thrashing
        // the CPU. With limitedParallelism(1), only one render runs at a time;
        // StateFlow already conflates by definition, so when a render is in
        // flight, intermediate desired-frame values are dropped and the next
        // render picks up whatever the latest value is when the worker frees.
        viewModelScope.launch(Dispatchers.Default.limitedParallelism(1)) {
            for (frame in renderRequests) {
                val renderer = waveformRenderer ?: continue
                val samples = renderer.renderCentered(frame)
                _uiState.update { it.copy(waveformSamples = samples) }
            }
        }

        viewModelScope.launch {
            audioPlayer.events.collect { event ->
                when (event) {
                    AudioPlayerEvent.Play -> {
                        _uiState.value = _uiState.value.copy(isPlaying = true, error = null)
                        startTicker()
                    }

                    AudioPlayerEvent.Pause -> {
                        // Do NOT refreshTransport() here. Once the sink stops, the
                        // player reports the WRITTEN position (ahead of what was
                        // actually heard by the whole audio buffer, ~200 ms), so
                        // snapping to it jumps the cursor forward. togglePlayPause
                        // already set currentFrame to the played position.
                        _uiState.value = _uiState.value.copy(isPlaying = false)
                        stopTicker()
                    }

                    AudioPlayerEvent.Stop,
                    AudioPlayerEvent.Complete -> {
                        _uiState.value = _uiState.value.copy(isPlaying = false)
                        stopTicker()
                        refreshTransport()
                        refreshWaveform()
                    }

                    is AudioPlayerEvent.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isPlaying = false,
                            error = event.message
                        )
                        stopTicker()
                    }

                    else -> Unit
                }
            }
        }
    }

    fun loadTarget(
        sourceId: Int,
        targetId: Int,
        chapterNumber: Int,
        unitNumber: Int,
        takeNumber: Int?
    ) {
        requestedTakeNumber = takeNumber
        viewModelScope.launch(Dispatchers.IO) {
            val projects = workbookRepository.getProjectsSuspend()
            val foundWorkbook = projects.find {
                it.source.collectionId == sourceId && it.target.collectionId == targetId
            } ?: return@launch

            val chapterList = foundWorkbook.target.chapters.toList().await()
                .sortedBy { it.sort }
            if (chapterList.isEmpty()) return@launch

            val expandedTargets = mutableListOf<PlaybackTarget>()
            chapterList.forEach { chapter ->
                expandedTargets.add(PlaybackTarget(chapter = chapter, chunk = null))
                chapter.chunksSuspend().sortedBy { it.sort }.forEach { chunk ->
                    expandedTargets.add(PlaybackTarget(chapter = chapter, chunk = chunk))
                }
            }

            val desiredUnit = if (unitNumber == -1) null else unitNumber
            val initialIndex = expandedTargets.indexOfFirst { target ->
                if (target.chapter.sort != chapterNumber) return@indexOfFirst false
                if (desiredUnit == null) target.chunk == null else target.chunk?.sort == desiredUnit
            }

            workbook = foundWorkbook
            targets = expandedTargets
            switchToTarget(if (initialIndex >= 0) initialIndex else 0, force = true)
        }
    }

    fun setWaveformWidth(width: Int) {
        if (width <= 0 || width == waveformWidth) return
        waveformWidth = width
        activeTake?.let {
            setupWaveformRenderer(it)
            refreshWaveform()
        }
    }

    fun setMinimapWidth(width: Int) {
        if (width <= 0 || width == minimapWidth) return
        minimapWidth = width
        activeTake?.let { loadMinimapSamples(it) }
    }

    fun togglePlayPause() {
        if (_uiState.value.selectedTake == null) return
        if (_uiState.value.isPlaying) {
            // Capture the PLAYED position while the sink is still running. After
            // pause the player reports the WRITTEN position (ahead by the audio
            // buffer), so reading it post-pause is what jumped the cursor forward.
            val playedFrame = audioPlayer.getLocationInFrames()
            _uiState.value = _uiState.value.copy(isPlaying = false)
            stopTicker()
            audioPlayer.pause()
            applyTransportForFrame(playedFrame)
            requestRender(playedFrame)
        } else {
            _uiState.value = _uiState.value.copy(isPlaying = true, error = null)
            startTicker()
            audioPlayer.play()
        }
    }

    fun seekToProgress(progress: Float) {
        val duration = audioPlayer.getDurationInFrames()
        if (duration <= 0) return
        // Route through seekToFrame so the waveform centers on the exact requested
        // frame (not the player's chunk-rounded read-back), fixing imprecise
        // minimap taps.
        seekToFrame((duration * progress.coerceIn(0f, 1f)).toInt())
    }

    fun seekBackward() {
        // Base the delta on the authoritative UI frame, not the player's async
        // (possibly stale) position, then let seekToFrame own the state update.
        seekToFrame(_uiState.value.currentFrame - 5 * waveformSampleRate)
    }

    fun seekForward() {
        seekToFrame(_uiState.value.currentFrame + 5 * waveformSampleRate)
    }

    fun seekToFrame(absoluteFrame: Int) {
        val duration = audioPlayer.getDurationInFrames()
        if (duration <= 0) return
        val target = absoluteFrame.coerceIn(0, duration)
        audioPlayer.seek(target)
        // Derive all transport state from `target` directly. audioPlayer.seek() is
        // asynchronous, so reading back getLocationInFrames() here (as
        // refreshTransport does) would return the PRE-seek position and clobber the
        // playhead back to where it started — which made seeks land short of the
        // target and drags never reach the end.
        applyTransportForFrame(target)
        requestRender(target)
        // Re-anchor the interpolated playback clock at the new position so the
        // cursor snaps immediately instead of waiting for the ~500 ms drift check.
        if (_uiState.value.isPlaying) {
            stopTicker()
            startTicker()
        }
    }

    fun seekByFrameDelta(deltaFrames: Int) {
        seekToFrame(_uiState.value.currentFrame + deltaFrames)
    }

    fun showMinimap(show: Boolean) {
        _uiState.value = _uiState.value.copy(showMinimap = show)
    }

    fun markSelectionStartAtCurrent() {
        selectionStartFrame = audioPlayer.getLocationInFrames()
        // Reset end mark if start moved past it
        val end = selectionEndFrame
        if (end != null && selectionStartFrame != null && end <= selectionStartFrame!!) {
            selectionEndFrame = null
        }
        updateEditUi()
    }

    fun markSelectionEndAtCurrent() {
        val start = selectionStartFrame ?: return
        val current = audioPlayer.getLocationInFrames()
        if (current != start) {
            selectionEndFrame = current
            updateEditUi()
        }
    }

    fun setSelectionStartAtProgress(progress: Float) {
        val dur = editSession?.editedTotalFrames ?: return
        selectionStartFrame = (progress * dur).toInt().coerceIn(0, dur)
        val end = selectionEndFrame
        if (end != null && end <= selectionStartFrame!!) selectionEndFrame = null
        updateEditUi()
    }

    fun setSelectionEndAtProgress(progress: Float) {
        val dur = editSession?.editedTotalFrames ?: return
        val start = selectionStartFrame ?: return
        val frame = (progress * dur).toInt().coerceIn(0, dur)
        if (frame != start) { selectionEndFrame = frame; updateEditUi() }
    }

    // ── Playback-follow freeze (shared by every waveform drag) ─────────────────
    // While dragging (scrub OR a marker) the auto-follow ticker must not keep
    // re-centering the waveform, or the drawn content shifts out from under the
    // finger. Freezing stops the ticker (audio keeps playing); resuming re-anchors
    // and follows again if it was playing. Idempotent when paused (no-ops).

    fun freezePlaybackFollow() {
        scrubWasPlaying = _uiState.value.isPlaying
        stopTicker()
    }

    fun resumePlaybackFollow() {
        if (scrubWasPlaying) startTicker()
        scrubWasPlaying = false
    }

    // ── Waveform live scrub (drag) ─────────────────────────────────────────────
    // Keeps audio playing while dragging. scrubToFrame re-renders and updates the
    // readout to the dragged position without seeking; on release endWaveformScrub
    // commits the seek and resumes following if it was playing.

    fun scrubToFrame(frame: Int) {
        if (audioPlayer.getDurationInFrames() <= 0) return
        // No audioPlayer.seek during the drag — audio keeps playing; we only move
        // the displayed position and re-render.
        applyTransportForFrame(frame)
        requestRender(frame.coerceIn(0, audioPlayer.getDurationInFrames()))
    }

    fun endWaveformScrub(finalFrame: Int) {
        val duration = audioPlayer.getDurationInFrames()
        if (duration <= 0) return
        val target = finalFrame.coerceIn(0, duration)
        audioPlayer.seek(target)
        // Derive from `target`, not the async-stale player position (see seekToFrame).
        applyTransportForFrame(target)
        requestRender(target)
        resumePlaybackFollow()
    }

    // ── Verse marker drag ─────────────────────────────────────────────────────
    // Mirrors the start/end selection-marker path: a per-move progress callback
    // updates a single slot in `editedVerseMarkers` (no re-sort during the drag so
    // the captured index stays valid) and mirrors it into the UI. On release the
    // list is sorted and written back into `baseMarkers` so the moved location
    // survives later re-renders and edit/save.

    fun beginVerseMarkerDrag(index: Int) {
        draggingVerseMarkerIndex = if (index in editedVerseMarkers.indices) index else -1
    }

    fun moveVerseMarker(progress: Float) {
        val idx = draggingVerseMarkerIndex
        if (idx !in editedVerseMarkers.indices) return
        val dur = _uiState.value.durationFrames.takeIf { it > 0 } ?: return
        editedVerseMarkers[idx] = (progress * dur).toInt().coerceIn(0, dur)
        publishVerseMarkers()
    }

    fun endVerseMarkerDrag() {
        if (draggingVerseMarkerIndex < 0) return
        draggingVerseMarkerIndex = -1
        editedVerseMarkers.sort()
        commitVerseMarkersToBase()
        publishVerseMarkers()
    }

    // Push the current edited verse-marker frames + labels into the UI state.
    private fun publishVerseMarkers() {
        markerFrames = editedVerseMarkers.toList()
        markerLabels = buildMarkerLabelsForFrames(markerFrames)
        _uiState.update { it.copy(
            markerFrames = markerFrames,
            markerLabels = markerLabels
        ) }
    }

    // Replace the VerseMarker entries in baseMarkers with the edited locations so
    // refreshMarkerFrames / save reflect the moved positions. Preserves any
    // non-verse markers (book/chapter) untouched.
    //
    // Only safe when there are no pending edits: baseMarkers are in absolute
    // (original-file) frames, whereas editedVerseMarkers are in edited/relative
    // frames once a cut exists. Writing relative frames back into the absolute base
    // would corrupt them (mapEditedMarkers would map them a second time). With edits
    // active we leave baseMarkers alone; the moved positions remain visible via
    // editedVerseMarkers until the next reload.
    private fun commitVerseMarkersToBase() {
        if (editSession?.hasEdits() == true) return
        val nonVerse = baseMarkers.filter { it !is VerseMarker }
        val oldVerses = baseMarkers.filterIsInstance<VerseMarker>().sortedBy { it.location }
        val movedVerses = editedVerseMarkers.sorted().mapIndexed { i, frame ->
            oldVerses.getOrNull(i)?.clone(frame) ?: VerseMarker(start = i + 1, end = i + 1, location = frame)
        }
        baseMarkers = (nonVerse + movedVerses).sortedBy { it.location }
    }

    fun clearSelection() {
        selectionStartFrame = null
        selectionEndFrame = null
        updateEditUi()
    }

    fun cutSelection() {
        val start = selectionStartFrame ?: return
        val end = selectionEndFrame ?: return
        val session = editSession ?: return
        if (abs(end - start) < 2) return
        val seekTo = min(start, end)
        if (session.cutRelative(start, end)) {
            selectionStartFrame = null
            selectionEndFrame = null
            reloadCurrentTakePlayback(seekTo.coerceAtMost(session.editedTotalFrames))
        }
    }

    fun undoEdit() {
        val session = editSession ?: return
        if (!session.undo()) return
        val seekTo = audioPlayer.getLocationInFrames().coerceAtMost(session.editedTotalFrames)
        reloadCurrentTakePlayback(seekTo)
    }

    fun redoEdit() {
        val session = editSession ?: return
        if (!session.redo()) return
        val seekTo = audioPlayer.getLocationInFrames().coerceAtMost(session.editedTotalFrames)
        reloadCurrentTakePlayback(seekTo)
    }

    fun saveCurrentEditsAsNewTake() {
        val take = activeTake ?: return
        val session = editSession ?: return
        if (!session.hasEdits()) {
            // getString is suspend; resolve in a coroutine (this guard sits in a
            // synchronous function body before the IO launch below).
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(error = getString(Res.string.err_no_edits_to_save))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tempEditedWav = File.createTempFile("edited_", ".wav")
                val reader = buildReaderForTake(take)
                val markers = mapEditedMarkers(baseMarkers)
                audioBouncer.bounceAudio(tempEditedWav, reader, markers)
                persistEditedFileAsNewTake(tempEditedWav)
                tempEditedWav.delete()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: getString(Res.string.err_save_edited_take)
                )
            }
        }
    }

    fun enterVerseMarkerMode() {
        if (_uiState.value.selectedTake == null) return
        droppedVerseMarkerFrames.clear()
        _uiState.value = _uiState.value.copy(
            isVerseMarkerMode = true,
            versesMarked = 0
        )
    }

    fun exitVerseMarkerMode() {
        droppedVerseMarkerFrames.clear()
        _uiState.value = _uiState.value.copy(
            isVerseMarkerMode = false,
            versesMarked = 0
        )
    }

    fun dropVerseMarkerAtCurrentPosition() {
        val frame = audioPlayer.getLocationInFrames()
        droppedVerseMarkerFrames.add(frame)
        val allMarkerFrames = (markerFrames + droppedVerseMarkerFrames).distinct().sorted()
        val allMarkerLabels = buildMarkerLabelsForFrames(allMarkerFrames)
        _uiState.value = _uiState.value.copy(
            versesMarked = droppedVerseMarkerFrames.size,
            markerFrames = allMarkerFrames,
            markerLabels = allMarkerLabels
        )
    }

    fun saveVerseMarkersAsNewTake() {
        if (droppedVerseMarkerFrames.isEmpty()) {
            exitVerseMarkerMode()
            return
        }
        val take = activeTake ?: return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tempWav = File.createTempFile("marked_", ".wav")
                val reader = buildReaderForTake(take)
                val allMarkerFrames = (markerFrames + droppedVerseMarkerFrames).distinct().sorted()
                val newMarkers = allMarkerFrames.mapIndexed { idx, frame ->
                    VerseMarker(start = idx + 1, end = idx + 1, location = frame)
                }
                audioBouncer.bounceAudio(tempWav, reader, newMarkers)
                persistEditedFileAsNewTake(tempWav)
                tempWav.delete()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: getString(Res.string.err_save_verse_markers)
                )
            }
        }
        exitVerseMarkerMode()
    }

    fun onRerecord() {
        val wb = workbook ?: return
        val target = currentTarget() ?: return
        _navEvents.tryEmit(
            NavEvent.Rerecord(
                sourceId = wb.source.collectionId,
                targetId = wb.target.collectionId,
                chapterNumber = target.chapter.sort,
                unitNumber = target.chunk?.sort ?: 0
            )
        )
    }

    fun onInsert() {
        val wb = workbook ?: return
        val target = currentTarget() ?: return
        _navEvents.tryEmit(
            NavEvent.Insert(
                sourceId = wb.source.collectionId,
                targetId = wb.target.collectionId,
                chapterNumber = target.chapter.sort,
                unitNumber = target.chunk?.sort ?: 0
            )
        )
    }

    /**
     * "Accept/keep" action (the check button). Decision is owned here, based on
     * the authoritative edit session: with pending edits, save them as a new
     * take (a successful save drives the screen back via [editedTakeSavedEvents]);
     * otherwise the current take is already kept, so just exit to the unit list.
     */
    fun acceptTake() {
        if (editSession?.hasEdits() == true) {
            saveCurrentEditsAsNewTake()
        } else {
            _navEvents.tryEmit(NavEvent.Exit)
        }
    }

    /**
     * Back affordance (on-screen arrow + system back). The outcome is decided
     * from model state rather than in the view:
     *   - in verse-marker mode, leave that sub-mode and stay on the editor;
     *   - with unsaved edits, ask the view to confirm (save/discard);
     *   - otherwise exit to the unit list.
     */
    fun onBackRequested() {
        when {
            _uiState.value.isVerseMarkerMode -> exitVerseMarkerMode()
            editSession?.hasEdits() == true -> _navEvents.tryEmit(NavEvent.ConfirmExit)
            else -> _navEvents.tryEmit(NavEvent.Exit)
        }
    }

    /** Leave the editor, abandoning any pending edits (the confirm dialog's "Discard"). */
    fun exitWithoutSaving() {
        _navEvents.tryEmit(NavEvent.Exit)
    }

    private suspend fun persistEditedFileAsNewTake(editedAudioFile: File) {
        val wb = workbook ?: return
        val target = currentTarget() ?: return
        val audio = associatedAudio ?: return

        if (!editedAudioFile.exists()) {
            throw IllegalStateException("Edited audio file does not exist")
        }

        val newTakeNumber = audio.getNewTakeNumberSuspend()
        val namer = WorkbookFileNamerBuilder.createFileNamer(
            workbook = wb,
            chapter = target.chapter,
            chunk = target.chunk,
            recordable = target.recordable,
            rcSlug = wb.sourceMetadataSlug
        )

        val filename = namer.generateName(newTakeNumber, AudioFileFormat.WAV)
        val takeDir = wb.projectFilesAccessor.getChapterAudioDir(wb, target.chapter)
        val newTake = takeCreator.createNewTake(
            newTakeNumber = newTakeNumber,
            filename = filename,
            audioDir = takeDir,
            createEmpty = false
        )

        editedAudioFile.copyTo(newTake.file, overwrite = true)
        audio.insertTake(newTake)
        audio.selectTake(newTake)
        _editedTakeSavedEvents.tryEmit(newTake.number)
    }

    private fun currentTarget(): PlaybackTarget? = targets.getOrNull(currentTargetIndex)

    private fun switchToTarget(index: Int, force: Boolean = false) {
        if (index !in targets.indices) return
        if (!force && _uiState.value.isPlaying) return

        currentTargetIndex = index
        val wb = workbook ?: return
        val target = targets[index]
        associatedAudio = target.recordable.audio

        audioPlayer.pause()
        stopTicker()
        closeWaveformRenderer()
        closeMinimapRenderer()
        markerFrames = emptyList()
        markerLabels = emptyList()
        baseMarkers = emptyList()
        activeTake = null
        editSession = null
        selectionStartFrame = null
        selectionEndFrame = null
        droppedVerseMarkerFrames.clear()

        _uiState.value = _uiState.value.copy(
            selectedTake = null,
            takes = emptyList(),
            currentTakeNumber = null,
            waveformSamples = floatArrayOf(),
            minimapSamples = floatArrayOf(),
            progress = 0f,
            currentFrame = 0,
            durationFrames = 0,
            sampleRate = 44100,
            markerFrames = emptyList(),
            markerLabels = emptyList(),
            elapsedText = "00:00:00",
            durationText = "00:00:00",
            isVerseMarkerMode = false,
            versesMarked = 0,
            error = null
        )

        updateTargetUi(target, wb)
        updateEditUi()
        observeTargetAudio()

        // Resolve and load source audio for this target. Disk-bound work goes off
        // the main thread; the controller's state flow drives the UI.
        viewModelScope.launch(Dispatchers.IO) {
            val available = sourceAudioController.load(wb, target.chapter, target.chunk)
            _uiState.update { it.copy(sourceAudioAvailable = available) }
        }
    }

    fun toggleSourcePlayback() {
        sourceAudioController.togglePlayPause()
    }

    fun seekSourceToProgress(progress: Float) {
        sourceAudioController.seekToProgress(progress)
    }

    private fun updateTargetUi(target: PlaybackTarget, wb: Workbook) {
        _uiState.value = _uiState.value.copy(
            targetUi = TargetUiState(
                languageLabel = wb.target.language.name,
                projectLabel = wb.target.resourceMetadata.identifier.uppercase(),
                bookLabel = wb.target.title,
                chapterValue = target.chapter.sort.toString(),
                unitValue = (target.chunk?.sort ?: 0).toString()
            )
        )
    }

    private fun observeTargetAudio() {
        takesJob?.cancel()
        selectedJob?.cancel()

        val audio = associatedAudio ?: return
        val takeMap = linkedMapOf<Int, Take>()

        takesJob = viewModelScope.launch {
            audio.takesFlow.collect { take ->
                takeMap[take.number] = take
                val takes = takeMap.values
                    .filter { !it.isDeleted() }
                    .sortedBy { it.number }
                _uiState.value = _uiState.value.copy(takes = takes)

                val selected = _uiState.value.selectedTake
                if (selected == null || selected.isDeleted()) {
                    val requested = requestedTakeNumber?.let { req -> takes.find { it.number == req } }
                    val fallback = requested ?: takes.maxByOrNull { it.number }
                    if (fallback != null) {
                        audio.selectTake(fallback)
                        requestedTakeNumber = null
                    }
                }
            }
        }

        selectedJob = viewModelScope.launch {
            audio.selectedFlow.collect { selectedHolder ->
                val selectedTake = selectedHolder.value
                _uiState.value = _uiState.value.copy(selectedTake = selectedTake)
                if (selectedTake != null && !selectedTake.isDeleted()) {
                    loadTakeForPlayback(selectedTake)
                }
            }
        }
    }

    private fun loadTakeForPlayback(take: Take) {
        runCatching {
            activeTake = take
            createFreshEditSession(take)
            val originalAudio = OratureAudioFile(take.file)
            baseMarkers = originalAudio.getMarkers().sortedBy { it.location }
            markerFrames = baseMarkers.filterIsInstance<VerseMarker>().map { it.location }
            markerLabels = baseMarkers.filterIsInstance<VerseMarker>().map { it.label }
            editedVerseMarkers.clear()
            editedVerseMarkers.addAll(markerFrames)
            droppedVerseMarkerFrames.clear()
            selectionStartFrame = null
            selectionEndFrame = null
            reloadCurrentTakePlayback(0)
        }.onFailure { e ->
            // loadTakeForPlayback is synchronous; getString is suspend, so resolve
            // the fallback in a coroutine.
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(error = e.message ?: getString(Res.string.err_load_take))
            }
        }
    }

    private fun buildMarkerLabelsForFrames(frames: List<Int>): List<String> {
        // Reconstruct labels by matching existing base markers, then numbering new ones
        val existingByFrame = baseMarkers.filterIsInstance<VerseMarker>()
            .associate { it.location to it.label }
        return frames.map { frame -> existingByFrame[frame] ?: "+" }
    }

    private fun createFreshEditSession(take: Take) {
        val totalFrames = OratureAudioFile(take.file).totalFrames
        editSession = WaveEditSession(totalFrames)
    }

    private fun buildReaderForTake(take: Take): AudioFileReader {
        val baseReader = OratureAudioFile(take.file).reader()
        val ranges = editSession?.rangesSnapshot().orEmpty()
        return if (ranges.isEmpty()) {
            baseReader
        } else {
            CutAwareAudioFileReader(baseReader, ranges)
        }
    }

    private fun mapEditedMarkers(markers: List<AudioMarker>): List<AudioMarker> {
        val session = editSession ?: return markers
        if (!session.hasEdits()) return markers

        return markers.mapNotNull { marker ->
            if (session.isFrameRemoved(marker.location)) {
                null
            } else {
                marker.clone(session.absoluteToRelative(marker.location))
            }
        }.sortedBy { it.location }
    }

    private fun refreshMarkerFrames() {
        val editedMarkers = mapEditedMarkers(baseMarkers).filterIsInstance<VerseMarker>()
        markerFrames = editedMarkers.map { it.location }.sorted()
        markerLabels = editedMarkers.sortedBy { it.location }.map { it.label }
        editedVerseMarkers.clear()
        editedVerseMarkers.addAll(markerFrames)
        _uiState.update { it.copy(
            markerFrames = markerFrames,
            markerLabels = markerLabels
        ) }
    }

    private fun reloadCurrentTakePlayback(seekFrame: Int) {
        val take = activeTake ?: return
        audioPlayer.pause()
        stopTicker()

        val reader = buildReaderForTake(take)
        waveformSampleRate = reader.spec.sampleRate
        val durationFrames = reader.totalFrames
        val clampedSeek = seekFrame.coerceIn(0, durationFrames)

        audioPlayer.load(reader)
        audioPlayer.seek(clampedSeek)

        setupWaveformRenderer(take)
        loadMinimapSamples(take)
        refreshMarkerFrames()
        // Derive transport + waveform center from the known seek target, not the
        // async-stale player position (matters when reloading with a non-zero seek
        // after a cut/undo/redo).
        applyTransportForFrame(clampedSeek)
        requestRender(clampedSeek)
        updateEditUi()
    }

    private fun setupWaveformRenderer(take: Take) {
        closeWaveformRenderer()
        if (waveformWidth <= 0) return
        val reader = buildReaderForTake(take)
        waveformSampleRate = reader.spec.sampleRate
        reader.open()
        waveformRenderer = PlaybackWaveformRenderer(
            reader = reader,
            width = waveformWidth,
            secondsOnScreen = 10
        )
    }

    private fun loadMinimapSamples(take: Take) {
        closeMinimapRenderer()
        if (minimapWidth <= 0) return
        minimapRenderJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val reader = buildReaderForTake(take)
                reader.open()
                val renderer = MinimapWaveformRenderer(reader = reader, width = minimapWidth)
                minimapRenderer = renderer
                val samples = renderer.render()
                _uiState.update { it.copy(minimapSamples = samples) }
            }
        }
    }

    private fun updateEditUi() {
        val duration = audioPlayer.getDurationInFrames().coerceAtLeast(1)
        val startProgress = selectionStartFrame
            ?.coerceIn(0, duration)
            ?.let { it.toFloat() / duration.toFloat() }
        val endProgress = selectionEndFrame
            ?.coerceIn(0, duration)
            ?.let { it.toFloat() / duration.toFloat() }
        val canCut = selectionStartFrame != null &&
            selectionEndFrame != null &&
            abs((selectionStartFrame ?: 0) - (selectionEndFrame ?: 0)) >= 2

        // Atomic update: this runs on the Main-thread ticker path while the
        // waveform render worker concurrently updates waveformSamples on a
        // background thread. A blind `.value =` here would clobber a just-finished
        // samples update; `.update {}` CAS-retries so neither write is lost.
        _uiState.update { it.copy(
            selectionStartProgress = startProgress,
            selectionEndProgress = endProgress,
            canCutSelection = canCut,
            canUndoEdit = editSession?.canUndo() == true,
            canRedoEdit = editSession?.canRedo() == true
        ) }
    }

    private fun refreshWaveform() {
        // Center on the authoritative displayed frame. Callers first sync
        // currentFrame (refreshTransport at pause, etc.), so this avoids depending
        // on the player's async/pollable position.
        requestRender(_uiState.value.currentFrame)
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            // Playback clock that stays LOCKED to the real played position while
            // smoothing its coarse updates. getLocationInFrames() advances on the
            // audio HAL's chunk boundary (~10 ms), so we re-anchor to it whenever it
            // moves forward and use the wall clock only to fill the gap up to the
            // next 16 ms display tick. The old approach anchored once and let
            // wall-clock free-run, which drifted ~200 ms from the true position and
            // produced a visible jump when pause snapped the cursor back to reality.
            // Anchoring on the real position keeps display ≈ audible position, so
            // pausing has (essentially) nothing to snap.
            val sampleRate = waveformSampleRate.coerceAtLeast(1).toLong()
            var anchorFrame = _uiState.value.currentFrame.toLong()
            var anchorNs = System.nanoTime()

            while (_uiState.value.isPlaying) {
                delay(16)
                val nowNs = System.nanoTime()

                val observed = audioPlayer.getLocationInFrames().toLong()
                // Lock onto the real position whenever it has advanced past our
                // anchor. Only moves forward, so a momentarily-behind reading can't
                // yank the cursor backward (which would read as stutter).
                if (observed > anchorFrame) {
                    anchorFrame = observed
                    anchorNs = nowNs
                }

                val displayFrameLong = anchorFrame + (nowNs - anchorNs) * sampleRate / 1_000_000_000L
                refreshTransportInterpolated(displayFrameLong, sampleRate.toInt())
                requestRender(_uiState.value.currentFrame)
            }
        }
    }

    // Variant of refreshTransport that uses an externally-supplied display frame
    // (from the interpolated playback clock) instead of polling the player's
    // chunky cursor. Derives positionMs from the frame + sample rate so the time
    // readout advances smoothly too.
    private fun refreshTransportInterpolated(displayFrameLong: Long, sampleRate: Int) {
        val durationFrames = audioPlayer.getDurationInFrames().coerceAtLeast(0)
        val durationMs = audioPlayer.getDurationMs().coerceAtLeast(0)
        val sr = sampleRate.coerceAtLeast(1)
        val clampedFrame = displayFrameLong.coerceIn(0L, durationFrames.toLong()).toInt()
        val positionMs = (clampedFrame.toLong() * 1000L / sr).toInt().coerceIn(0, durationMs)
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

        // Atomic: runs 60 Hz on the ticker thread alongside the background render
        // worker's samples update — CAS-retry so neither clobbers the other.
        _uiState.update { it.copy(
            progress = progress,
            currentFrame = clampedFrame,
            durationFrames = durationFrames,
            sampleRate = sr,
            elapsedMs = positionMs,
            durationMs = durationMs,
            elapsedText = formatTime(positionMs),
            durationText = formatTime(durationMs),
            currentTakeNumber = it.selectedTake?.number
        ) }
        updateEditUi()
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // Sets the transport UI (position, progress, time readout) from an EXPLICIT
    // frame rather than polling the player. Use after audioPlayer.seek(), whose
    // async nature means getLocationInFrames() would still report the old position.
    // Duration is safe to read from the player (it reflects the loaded reader, not
    // the play head).
    private fun applyTransportForFrame(frame: Int) {
        val durationFrames = audioPlayer.getDurationInFrames().coerceAtLeast(0)
        val durationMs = audioPlayer.getDurationMs().coerceAtLeast(0)
        val sr = waveformSampleRate.coerceAtLeast(1)
        val clamped = frame.coerceIn(0, durationFrames)
        val positionMs = (clamped.toLong() * 1000L / sr).toInt().coerceIn(0, durationMs)
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        _uiState.update { it.copy(
            progress = progress,
            currentFrame = clamped,
            durationFrames = durationFrames,
            sampleRate = sr,
            elapsedMs = positionMs,
            durationMs = durationMs,
            elapsedText = formatTime(positionMs),
            durationText = formatTime(durationMs),
            currentTakeNumber = it.selectedTake?.number
        ) }
        updateEditUi()
    }

    private fun refreshTransport() {
        val durationMs = audioPlayer.getDurationMs().coerceAtLeast(0)
        val positionMs = audioPlayer.getLocationMs().coerceIn(0, durationMs)
        val durationFrames = audioPlayer.getDurationInFrames().coerceAtLeast(0)
        val currentFrame = audioPlayer.getLocationInFrames().coerceIn(0, durationFrames)
        val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

        _uiState.update { it.copy(
            progress = progress,
            currentFrame = currentFrame,
            durationFrames = durationFrames,
            sampleRate = waveformSampleRate,
            elapsedMs = positionMs,
            durationMs = durationMs,
            elapsedText = formatTime(positionMs),
            durationText = formatTime(durationMs),
            currentTakeNumber = it.selectedTake?.number
        ) }
        updateEditUi()
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun closeWaveformRenderer() {
        waveformRenderer?.close()
        waveformRenderer = null
    }

    private fun closeMinimapRenderer() {
        minimapRenderJob?.cancel()
        minimapRenderJob = null
        minimapRenderer?.close()
        minimapRenderer = null
    }

    fun cleanup() {
        stopTicker()
        takesJob?.cancel()
        selectedJob?.cancel()
        closeWaveformRenderer()
        closeMinimapRenderer()
        sourceAudioController.release()
        audioPlayer.release()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
