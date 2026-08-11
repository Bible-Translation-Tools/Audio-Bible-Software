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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import androidx.compose.runtime.IntState
import androidx.compose.runtime.mutableIntStateOf
import org.bibletranslationtools.shared.audio.engine.AudioTimeline
import org.bibletranslationtools.shared.audio.engine.FilePcmSource
import org.bibletranslationtools.shared.audio.engine.PcmSource
import org.bibletranslationtools.shared.audio.engine.TimelineAudioFileReader
import org.bibletranslationtools.shared.audio.engine.PlaybackDisplayPosition
import org.bibletranslationtools.shared.audio.engine.PlaybackPerfStats
import org.bibletranslationtools.shared.audio.engine.SourceAudioPlayerController
import org.bibletranslationtools.shared.audio.engine.WaveEditSession
import org.bibletranslationtools.shared.audio.engine.WaveformPeakCache
import org.bibletranslationtools.shared.audio.engine.buildPeakCache
import org.bibletranslationtools.shared.audio.engine.formatPlaybackTime
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.audio.BookMarker
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.BOOK_TITLE_SORT
import org.bibletranslationtools.otter.common.data.primitives.CHAPTER_TITLE_SORT
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.AudioFileReader
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.AudioBouncer
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.Recordable
import org.bibletranslationtools.otter.common.domain.content.SaveAudioAsNewTake
import org.bibletranslationtools.otter.common.domain.narration.AudioScene
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.err_no_edits_to_save
import org.bibletranslationtools.shared.resources.err_save_edited_take
import org.bibletranslationtools.shared.resources.err_save_verse_markers
import org.bibletranslationtools.shared.resources.err_load_take
import org.bibletranslationtools.shared.resources.err_record_device_start
import org.bibletranslationtools.bttrecorder2.services.InsertRecorder
import org.bibletranslationtools.bttrecorder2.services.UnitTarget
import org.bibletranslationtools.bttrecorder2.services.UnitTargetLoader
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Kind of a waveform marker, so the UI can render each type distinctly. */
enum class MarkerKind { BOOK, CHAPTER, VERSE }

/** Verse labels the take file accepts: `N` or `N-M`. See `EditMarker.toPersistableMarker`. */
private val VERSE_LABEL = Regex("""(\d+)(?:-(\d+))?""")

class PlaybackViewModel(
    private val unitTargetLoader: UnitTargetLoader,
    private val audioPlayerFactory: AudioPlayerConnectionFactory,
    private val saveAudioAsNewTake: SaveAudioAsNewTake,
    private val writeTakeMarkers: WriteTakeMarkers,
    private val audioBouncer: AudioBouncer,
    private val audioRecorderFactory: AudioRecorderConnectionFactory
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
        // Per-frame position (currentFrame/progress/elapsed) intentionally does NOT
        // live here: it flows through PlaybackDisplayPosition and is read only in draw
        // scopes / leaf composables, so playback does not recompose the screen.
        val durationFrames: Int = 0,
        val sampleRate: Int = 44100,
        val durationMs: Int = 0,
        val durationText: String = "00:00:00",
        val markerFrames: List<Int> = emptyList(),
        val markerLabels: List<String> = emptyList(),
        val markerKinds: List<MarkerKind> = emptyList(),
        val showMinimap: Boolean = true,
        val sourceAudioAvailable: Boolean = false,
        val selectionStartProgress: Float? = null,
        val selectionEndProgress: Float? = null,
        val canCutSelection: Boolean = false,
        val canUndoEdit: Boolean = false,
        val canRedoEdit: Boolean = false,
        /** An insert-at-playhead session is open (mic live, clip not yet spliced). */
        val isInsertActive: Boolean = false,
        /** The insert session is actively capturing (vs. armed/paused). */
        val isInsertRecording: Boolean = false,
        val isVerseMarkerMode: Boolean = false,
        // Number of verse markers still to place in verse-marker mode
        // (totalVerses − placed), mirroring the original app's "N Left" counter.
        val versesRemaining: Int = 0,
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

    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    // Every uiState emission funnels through here so PlaybackPerfStats can count them
    // (see PLAYBACK_PERF_STATS). Steady-state playback should emit nothing — position
    // flows through the display clock, not uiState.
    private inline fun updateState(block: (PlaybackUiState) -> PlaybackUiState) {
        PlaybackPerfStats.onEmission()
        _uiState.update(block)
    }

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
    private var targets: List<UnitTarget> = emptyList()
    private var currentTargetIndex = -1
    private var associatedAudio: AssociatedAudio? = null
    private var requestedTakeNumber: Int? = null

    private var takesJob: Job? = null
    private var selectedJob: Job? = null

    // ── Live-render data source ───────────────────────────────────────────────
    // Per-source in-memory min/max peaks (keyed by PcmSource.id). Built once per
    // source in the background; the draw loop reads these instead of disk, which is
    // what makes per-frame live rendering possible.
    private val peakCaches = mutableMapOf<String, WaveformPeakCache>()
    private var peakCacheJob: Job? = null

    // Bumped (main thread) whenever the timeline shape changes: take load and every
    // edit-session change (cut/undo/redo — all funnel through reloadCurrentTakePlayback).
    // The waveform's drawWithCache cache scope keys on this.
    val timelineGeneration = mutableIntStateOf(0)

    // Cached edited-timeline view for rendering; refreshed on every generation bump.
    // @Volatile: written on main, read from the draw phase.
    @Volatile
    private var renderTimelineCache: AudioTimeline? = null

    /** The timeline the waveform should render: the edit session's view of the take. */
    fun renderTimeline(): AudioTimeline? = renderTimelineCache

    fun peakCacheFor(source: PcmSource): WaveformPeakCache? = peakCaches[source.id]

    private fun refreshRenderTimeline() {
        renderTimelineCache = editSession?.timeline()
            ?: activeTake?.let { AudioTimeline.ofWholeSource(FilePcmSource(it.file)) }
    }

    private fun bumpTimelineGeneration() {
        refreshRenderTimeline()
        // Main.immediate runs synchronously when already on main, posts otherwise —
        // snapshot-state writes stay on the main thread either way.
        launchLogged(Dispatchers.Main.immediate) { timelineGeneration.intValue++ }
    }

    private var waveformWidth: Int = 0
    private var minimapWidth: Int = 0
    private var waveformSampleRate: Int = 44100
    private var markerFrames: List<Int> = emptyList()
    private var markerLabels: List<String> = emptyList()
    private var baseMarkers: List<AudioMarker> = emptyList()
    private var activeTake: Take? = null

    private var editSession: WaveEditSession? = null
    private var selectionStartFrame: Int? = null
    private var selectionEndFrame: Int? = null

    /** A marker being displayed/edited: its frame plus its kind + label. */
    private data class EditMarker(val frame: Int, val label: String, val kind: MarkerKind)

    /** Ordered spec for verse-marker mode: what each successive marker should be. */
    private data class MarkerSpec(val label: String, val kind: MarkerKind)

    // THE single authoritative list of markers (book/chapter/verse). Used for
    // display, dragging, dropping (verse-marker mode), and saving alike, so the
    // gesture's hit-test index always matches what's drawn.
    private val editedMarkers = mutableListOf<EditMarker>()
    private var draggingVerseMarkerIndex: Int = -1

    // Verse-marker mode: one marker per chunk the take covers. markerSpecs holds the
    // ordered (label, kind) for each — Book (chapter 1 only) → Chapter → Verses —
    // and totalVerses is how many markers to place.
    private var totalVerses: Int = 0
    private var markerSpecs: List<MarkerSpec> = emptyList()

    // Live-scrub state for waveform drag. While scrubbing the display clock stops
    // following playback (audio keeps playing); on release we commit the seek and,
    // if it was playing, resume following.
    private var scrubWasPlaying: Boolean = false

    // The display-side playback clock. The UI drives onFrame per display frame
    // (withFrameNanos in PlaybackScreen); the VM owns all control transitions
    // (seek/scrub/pause/freeze). All writes main-thread.
    val clock = PlaybackDisplayPosition(
        positionSource = { audioPlayer.getLocationInFrames().toLong() },
        positionReliable = { audioPlayer.isPositionReliable() }
    )

    init {
        // No-op unless PLAYBACK_PERF_STATS is enabled (see PlaybackPerfStats).
        PlaybackPerfStats.startLogging(viewModelScope)

        // The disk-render worker that used to live here is gone: the waveform is now
        // drawn live every frame from the in-memory WaveformPeakCache (see
        // PlaybackScreen), so there is nothing to render ahead of time.
        // PlaybackPerfStats renders/s reading 0 during playback is the point.

        launchLogged {
            audioPlayer.events.collect { event ->
                when (event) {
                    AudioPlayerEvent.Play -> {
                        updateState { it.copy(isPlaying = true, error = null) }
                        clock.startAdvancing()
                    }

                    AudioPlayerEvent.Pause -> {
                        // Do NOT poll the player here. Once the sink stops it reports
                        // the WRITTEN position (ahead of the audible one by the whole
                        // audio buffer). togglePlayPause already froze the clock at
                        // the displayed position.
                        updateState { it.copy(isPlaying = false) }
                        clock.advancing = false
                    }

                    AudioPlayerEvent.Stop -> {
                        updateState { it.copy(isPlaying = false) }
                        clock.advancing = false
                        clock.snapTo(clock.displayFrame)
                    }

                    AudioPlayerEvent.Complete -> {
                        updateState { it.copy(isPlaying = false) }
                        clock.advancing = false
                        clock.snapTo(clock.durationFrames)
                    }

                    is AudioPlayerEvent.Error -> {
                        updateState { it.copy(
                            isPlaying = false,
                            error = event.message
                        ) }
                        clock.advancing = false
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
        launchLogged(Dispatchers.IO) {
            // -1 is this screen's navigation encoding for "no specific unit" — the chapter-level
            // target. UnitTargetLoader takes a nullable unit instead.
            val loaded = unitTargetLoader.load(
                sourceId = sourceId,
                targetId = targetId,
                chapterNumber = chapterNumber,
                unitNumber = if (unitNumber == -1) null else unitNumber
            ) ?: return@launchLogged

            workbook = loaded.workbook
            targets = loaded.targets
            // No match falls back to the first target; the recorder deliberately differs here.
            switchToTarget(loaded.requestedIndex.takeIf { it >= 0 } ?: 0, force = true)
        }
    }

    fun setWaveformWidth(width: Int) {
        if (width <= 0 || width == waveformWidth) return
        // Resize costs nothing now: the peak cache is resolution-independent and the
        // draw loop derives frames-per-pixel from the canvas size each frame.
        waveformWidth = width
    }

    fun setMinimapWidth(width: Int) {
        if (width <= 0 || width == minimapWidth) return
        minimapWidth = width
    }

    fun togglePlayPause() {
        if (_uiState.value.selectedTake == null) return
        if (_uiState.value.isPlaying) {
            // Freeze at the DISPLAYED position. Post-pause the player reports the
            // WRITTEN position (ahead by the audio buffer), so it must not be polled
            // here — the clock already tracks the audible position.
            val playedFrame = clock.displayFrame.toInt()
            updateState { it.copy(isPlaying = false) }
            clock.advancing = false
            audioPlayer.pause()
            applyTransportForFrame(playedFrame)
        } else {
            updateState { it.copy(isPlaying = true, error = null) }
            audioPlayer.play()
            // startAdvancing, not `advancing = true`: replaying after the take finished rewinds
            // the player to 0, and the display has to be told or it stalls at the end.
            clock.startAdvancing()
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
        seekToFrame(clock.displayFrame.toInt() - 5 * waveformSampleRate)
    }

    fun seekForward() {
        seekToFrame(clock.displayFrame.toInt() + 5 * waveformSampleRate)
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
        applyTransportForFrame(target)   // snaps the display clock to the target
    }

    fun seekByFrameDelta(deltaFrames: Int) {
        seekToFrame(clock.displayFrame.toInt() + deltaFrames)
    }

    fun showMinimap(show: Boolean) {
        updateState { it.copy(showMinimap = show) }
    }

    fun markSelectionStartAtCurrent() {
        // Land where the user SEES the playhead (the display clock), not the player's
        // coarse cursor.
        selectionStartFrame = clock.displayFrame.toInt()
        // Reset end mark if start moved past it
        val end = selectionEndFrame
        if (end != null && selectionStartFrame != null && end <= selectionStartFrame!!) {
            selectionEndFrame = null
        }
        updateEditUi()
    }

    fun markSelectionEndAtCurrent() {
        val start = selectionStartFrame ?: return
        val current = clock.displayFrame.toInt()
        if (current != start) {
            selectionEndFrame = current
            updateEditUi()
        }
    }

    fun setSelectionStartAtProgress(progress: Float) {
        val dur = editSession?.editedTotalFrames ?: return
        selectionStartFrame = (progress * dur).toInt().coerceIn(0, dur)
        updateEditUi()
    }

    fun setSelectionEndAtProgress(progress: Float) {
        val dur = editSession?.editedTotalFrames ?: return
        selectionEndFrame = (progress * dur).toInt().coerceIn(0, dur)
        updateEditUi()
    }

    // ── Playback-follow freeze (shared by every waveform drag) ─────────────────
    // While dragging (scrub OR a marker) the display clock must not keep
    // re-centering the waveform, or the drawn content shifts out from under the
    // finger. Freezing stops the clock's advance (audio keeps playing); resuming
    // hard-snaps to the real position on the next frame (error > 250 ms) and
    // follows again if it was playing. Idempotent when paused (no-ops).

    fun freezePlaybackFollow() {
        scrubWasPlaying = _uiState.value.isPlaying
        clock.advancing = false
    }

    fun resumePlaybackFollow() {
        clock.advancing = scrubWasPlaying && _uiState.value.isPlaying
        scrubWasPlaying = false
    }

    // ── Waveform live scrub (drag) ─────────────────────────────────────────────
    // Keeps audio playing while dragging. scrubToFrame re-renders and updates the
    // readout to the dragged position without seeking; on release endWaveformScrub
    // commits the seek and resumes following if it was playing.

    fun scrubToFrame(frame: Int) {
        if (audioPlayer.getDurationInFrames() <= 0) return
        // No audioPlayer.seek during the drag — audio keeps playing; we only move
        // the displayed position (the waveform redraws live from the peak cache).
        applyTransportForFrame(frame)
    }

    fun endWaveformScrub(finalFrame: Int) {
        val duration = audioPlayer.getDurationInFrames()
        if (duration <= 0) return
        val target = finalFrame.coerceIn(0, duration)
        audioPlayer.seek(target)
        // Derive from `target`, not the async-stale player position (see seekToFrame).
        applyTransportForFrame(target)
        resumePlaybackFollow()
    }

    // ── Marker drag ───────────────────────────────────────────────────────────
    // Per-move progress callback updates a single slot in `editedMarkers` (no
    // re-sort mid-drag so the captured index stays valid); on release the list is
    // sorted, re-labeled (marker mode) or committed to baseMarkers (normal mode).

    fun beginVerseMarkerDrag(index: Int) {
        draggingVerseMarkerIndex = if (index in editedMarkers.indices) index else -1
    }

    fun moveVerseMarker(progress: Float) {
        val idx = draggingVerseMarkerIndex
        if (idx !in editedMarkers.indices) return
        val dur = _uiState.value.durationFrames.takeIf { it > 0 } ?: return
        val frame = (progress * dur).toInt().coerceIn(0, dur)
        editedMarkers[idx] = editedMarkers[idx].copy(frame = frame)
        publishMarkers()
    }

    fun endVerseMarkerDrag() {
        if (draggingVerseMarkerIndex < 0) return
        draggingVerseMarkerIndex = -1
        if (_uiState.value.isVerseMarkerMode) {
            resortAndRelabelForMarkerMode()
        } else {
            editedMarkers.sortBy { it.frame }
            commitMarkersToBase()
        }
        publishMarkers()
    }

    // In verse-marker mode, the leftmost marker is spec 0 (Book/Chapter), the next
    // is spec 1, etc. Re-derive each marker's label/kind from its sorted position so
    // the numbering stays correct regardless of the order they were dropped/dragged.
    private fun resortAndRelabelForMarkerMode() {
        editedMarkers.sortBy { it.frame }
        for (i in editedMarkers.indices) {
            markerSpecs.getOrNull(i)?.let { spec ->
                editedMarkers[i] = editedMarkers[i].copy(label = spec.label, kind = spec.kind)
            }
        }
    }

    // Push the current markers (frame + label + kind) into the UI state.
    private fun publishMarkers() {
        markerFrames = editedMarkers.map { it.frame }
        markerLabels = editedMarkers.map { it.label }
        val kinds = editedMarkers.map { it.kind }
        updateState { it.copy(
            markerFrames = markerFrames,
            markerLabels = markerLabels,
            markerKinds = kinds
        ) }
    }

    // Write the edited markers (all types) back into baseMarkers so a re-render /
    // save reflects moved positions. Only in normal (editor) mode without pending
    // cuts — in verse-marker mode the set is a fresh in-progress list, and with an
    // active cut the edited frames are relative and would corrupt the absolute base.
    private fun commitMarkersToBase() {
        if (_uiState.value.isVerseMarkerMode) return
        if (editSession?.hasEdits() == true) return
        val nonContent = baseMarkers.filter {
            it !is VerseMarker && it !is BookMarker && it !is ChapterMarker
        }
        val rebuilt = editedMarkers.map { it.toAudioMarker() }
        baseMarkers = (nonContent + rebuilt).sortedBy { it.location }
    }

    /**
     * The form these markers take when written to the take file.
     *
     * Differs from [toAudioMarker] on exactly one point, and deliberately: a verse label that is
     * not `N` or `N-M` is dropped rather than coerced. The write path used to go through
     * `OratureAudioFile.addVerseMarker`, which matched the label against that pattern and
     * silently ignored it on a miss, while [toAudioMarker] — which feeds the in-memory
     * [baseMarkers] list — falls back to verse 1. The two have always disagreed; extracting the
     * write into [WriteTakeMarkers] keeps the disagreement rather than quietly picking a side.
     */
    private fun EditMarker.toPersistableMarker(): AudioMarker? = when (kind) {
        MarkerKind.BOOK -> BookMarker(label, frame)
        MarkerKind.CHAPTER -> ChapterMarker(label.toIntOrNull() ?: 0, frame)
        MarkerKind.VERSE -> VERSE_LABEL.matchEntire(label.trim())?.let { match ->
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: start
            VerseMarker(start, end, frame)
        }
    }

    private fun EditMarker.toAudioMarker(): AudioMarker = when (kind) {
        MarkerKind.BOOK -> BookMarker(label, frame)
        MarkerKind.CHAPTER -> ChapterMarker(label.toIntOrNull() ?: 0, frame)
        MarkerKind.VERSE -> {
            val parts = label.split("-")
            val start = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val end = parts.getOrNull(1)?.toIntOrNull() ?: start
            VerseMarker(start, end, frame)
        }
    }

    fun clearSelection() {
        selectionStartFrame = null
        selectionEndFrame = null
        updateEditUi()
    }

    fun cutSelection() {
        val a = selectionStartFrame ?: return
        val b = selectionEndFrame ?: return
        val session = editSession ?: return
        if (abs(b - a) < 2) return
        // The handles may be dragged in either order; cut the ordered range.
        val start = min(a, b)
        val end = max(a, b)
        val seekTo = start
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
            launchLogged {
                updateState { it.copy(error = getString(Res.string.err_no_edits_to_save)) }
            }
            return
        }

        launchLogged(Dispatchers.IO) {
            runCatching {
                val tempEditedWav = File.createTempFile("edited_", ".wav")
                val reader = buildReaderForTake(take)
                val markers = mapEditedMarkers(baseMarkers)
                audioBouncer.bounceAudio(tempEditedWav, reader, markers)
                persistEditedFileAsNewTake(tempEditedWav)
                tempEditedWav.delete()
                // The spliced clips are now baked into the saved take's audio.
                discardPendingInsertClips()
            }.onFailure { e ->
                updateState { it.copy(
                    error = e.message ?: getString(Res.string.err_save_edited_take)
                ) }
            }
        }
    }

    // The ordered marker specs the CURRENT TAKE covers. One marker per chunk, each
    // carrying its kind + label. Depends on the take's scope:
    //  - a single verse/chunk take (target.chunk != null) → exactly ONE marker;
    //  - a whole-chapter take (target.chunk == null) → Book (chapter 1) → Chapter →
    //    one Verse per verse chunk, in BCV order (chunk.sort: -2 book, -1 chapter).
    private fun markerSpecsForCurrentTake(): List<MarkerSpec> {
        val target = currentTarget() ?: return emptyList()
        target.chunk?.let { return listOf(specForChunk(it, target.chapter.sort)) }
        return targets
            .filter { it.chapter.sort == target.chapter.sort }
            .mapNotNull { it.chunk }
            .sortedBy { it.sort }
            .map { specForChunk(it, target.chapter.sort) }
    }

    private fun specForChunk(chunk: Chunk, chapterSort: Int): MarkerSpec = when (chunk.sort) {
        BOOK_TITLE_SORT -> MarkerSpec(workbook?.target?.slug ?: "book", MarkerKind.BOOK)
        CHAPTER_TITLE_SORT -> MarkerSpec("$chapterSort", MarkerKind.CHAPTER)
        else -> MarkerSpec(chunk.title, MarkerKind.VERSE)
    }

    fun enterVerseMarkerMode() {
        if (_uiState.value.selectedTake == null) return
        // Start fresh (like the original): one marker per spec this take covers,
        // counting down from that total. The first marker (book/chapter/verse 1) is
        // auto-placed at frame 0.
        markerSpecs = markerSpecsForCurrentTake()
        totalVerses = markerSpecs.size
        editedMarkers.clear()
        markerSpecs.firstOrNull()?.let { editedMarkers.add(EditMarker(0, it.label, it.kind)) }
        updateState { it.copy(
            isVerseMarkerMode = true,
            versesRemaining = (totalVerses - editedMarkers.size).coerceAtLeast(0)
        ) }
        publishMarkers()
    }

    fun exitVerseMarkerMode() {
        editedMarkers.clear()
        updateState { it.copy(
            isVerseMarkerMode = false,
            versesRemaining = 0
        ) }
        // Restore the take's real markers for the normal (editor) view.
        refreshMarkerFrames()
    }

    fun dropVerseMarkerAtCurrentPosition() {
        // Cap at the take's marker count when known; if unknown (0), don't block.
        if (totalVerses > 0 && editedMarkers.size >= totalVerses) return
        val frame = clock.displayFrame.toInt()                // the visible playhead position
        if (editedMarkers.any { abs(it.frame - frame) < 2 }) return  // ignore a duplicate at the same spot
        val spec = markerSpecs.getOrNull(editedMarkers.size)
            ?: MarkerSpec("${editedMarkers.size + 1}", MarkerKind.VERSE)
        editedMarkers.add(EditMarker(frame, spec.label, spec.kind))
        resortAndRelabelForMarkerMode()
        publishMarkers()
        updateState {
            it.copy(versesRemaining = (totalVerses - editedMarkers.size).coerceAtLeast(0))
        }
    }

    fun saveVerseMarkers() {
        val take = activeTake ?: run { exitVerseMarkerMode(); return }
        if (editedMarkers.isEmpty()) {
            exitVerseMarkerMode()
            return
        }
        val toWrite = editedMarkers.sortedBy { it.frame }.mapNotNull { it.toPersistableMarker() }

        // Markers are metadata, not audio — write them as WAV cue chunks into the
        // EXISTING take file (Orature does exactly this; the PCM is unchanged). No
        // re-encode, no new take. Pause first so we're not writing while reading.
        audioPlayer.pause()
        clock.advancing = false

        launchLogged(Dispatchers.IO) {
            runCatching {
                // ALL_CUE_TYPES matches what the local clearMarkers() call did — it replaces the
                // file's marker metadata wholesale, including any CHUNK/LICENSE cues. Narration
                // deliberately preserves those; see WriteTakeMarkers.
                // execute() returns the markers re-read from the file, so the normal view shows
                // what actually landed rather than what we intended to write.
                baseMarkers = writeTakeMarkers.execute(
                    take.file,
                    toWrite,
                    WriteTakeMarkers.ALL_CUE_TYPES
                )
            }.onFailure { e ->
                updateState { it.copy(
                    error = e.message ?: getString(Res.string.err_save_verse_markers)
                ) }
            }
            updateState { it.copy(isVerseMarkerMode = false, versesRemaining = 0) }
            refreshMarkerFrames()
        }
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

    // ---- insert recording: capture a clip and splice it in at the playhead --------------------
    // Records in place (like narration's re-record) instead of bouncing out to the record screen:
    // the clip becomes its own timeline segment, so it lands on the same undo/redo history as cuts
    // and nothing is written to the take until the user saves.

    private val insertRecorder by lazy { InsertRecorder(audioRecorderFactory, viewModelScope) }

    /** Live mic waveform for the insert overlay; null when no insert session is open. */
    val insertRenderer: StateFlow<ActiveRecordingRenderer?> get() = insertRecorder.renderer

    /** Timeline frame the clip will be spliced at (captured when the session opens). */
    private var insertAtFrame: Int = 0

    /** Clips already spliced into the timeline but not yet written into a saved take. */
    private val pendingInsertClips = mutableListOf<File>()

    // Live composite waveform while capturing: AudioScene joins the edited take (read through the
    // timeline) with the incoming mic stream, exactly as narration does for re-record. Published as a
    // copied snapshot on a ~30 fps ticker so the Canvas never reads a buffer mid-refill.
    private var insertScene: AudioScene? = null
    private var insertSceneReader: AudioFileReader? = null
    private var insertWaveformJob: Job? = null
    @Volatile
    private var insertWaveformSnapshot: FloatArray = FloatArray(0)
    /** Bumped per published frame so the Canvas invalidates (draw-only, no recomposition). */
    private val insertWaveformGeneration = mutableIntStateOf(0)

    val insertWaveformGen: IntState get() = insertWaveformGeneration
    fun insertWaveform(): FloatArray = insertWaveformSnapshot

    private fun startInsertScene(take: Take, waveformWidth: Int) {
        stopInsertScene()
        val session = editSession ?: return
        val reader = TimelineAudioFileReader(session.timeline())
        runCatching { reader.open() }
        insertSceneReader = reader
        insertScene = AudioScene(
            reader,
            // The insert session's own connection, not the shared worker: one path to the mic.
            insertRecorder.audioStream,
            insertRecorder.isRecording,
            waveformWidth,
            secondsOnScreen = 10,
            recordingSampleRate = reader.spec.sampleRate
        )
        insertWaveformJob = launchLogged(Dispatchers.Default) {
            while (isActive) {
                val scene = insertScene
                if (scene != null) {
                    runCatching {
                        // Insert = a re-record over a ZERO-length region: the old audio resumes at the
                        // very frame the clip was spliced at, so both bounds are insertAtFrame.
                        val recorded = insertRecordedFrames()
                        val (buffer, _) = scene.getReRecordNarrationDrawable(
                            insertAtFrame + recorded,
                            insertAtFrame,
                            insertAtFrame
                        )
                        insertWaveformSnapshot = buffer.copyOf()
                        insertWaveformGeneration.value++
                    }
                }
                delay(33)
            }
        }
    }

    private fun stopInsertScene() {
        insertWaveformJob?.cancel()
        insertWaveformJob = null
        insertScene = null
        runCatching { insertSceneReader?.release() }
        insertSceneReader = null
        insertWaveformSnapshot = FloatArray(0)
    }

    /** Frames captured into the open clip so far, for the scene's write head. */
    private fun insertRecordedFrames(): Int = insertRecorder.recordedFramesSoFar()

    /** The take's own format — the clip must match it (see [InsertRecorder]). */
    private fun takeSpec(take: Take): AudioSpec {
        val audio = OratureAudioFile(take.file)
        return AudioSpec(
            sampleRate = audio.sampleRate,
            bitDepth = audio.bitsPerSample,
            channels = audio.channels
        )
    }

    /**
     * Opens an insert session at the current playhead: pauses playback and arms the mic, without
     * capturing yet ([startInsertRecording] starts). [waveformWidth] is the live waveform's pixel
     * width.
     */
    fun beginInsertAtPlayhead(waveformWidth: Int) {
        val take = activeTake ?: return
        if (_uiState.value.isInsertActive) return

        audioPlayer.pause()
        clock.advancing = false
        updateState { it.copy(isPlaying = false) }

        val total = editSession?.editedTotalFrames ?: audioPlayer.getDurationInFrames()
        insertAtFrame = clock.displayFrame.toInt().coerceIn(0, total)

        launchLogged {
            try {
                val clip = File.createTempFile("insert_", ".wav")
                insertRecorder.begin(clip, takeSpec(take), waveformWidth.coerceAtLeast(1))
                startInsertScene(take, waveformWidth.coerceAtLeast(1))
                updateState { it.copy(isInsertActive = true, isInsertRecording = false, error = null) }
            } catch (e: Exception) {
                logFailure("beginning an insert at the playhead", e)
                insertRecorder.discard()
                updateState {
                    it.copy(
                        isInsertActive = false,
                        isInsertRecording = false,
                        error = e.message ?: getString(Res.string.err_record_device_start)
                    )
                }
            }
        }
    }

    fun startInsertRecording() {
        if (!_uiState.value.isInsertActive) return
        insertRecorder.resume()
        updateState { it.copy(isInsertRecording = true) }
    }

    fun pauseInsertRecording() {
        if (!_uiState.value.isInsertActive) return
        insertRecorder.pause()
        updateState { it.copy(isInsertRecording = false) }
    }

    /** Closes the clip and splices it in at the captured playhead, leaving the playhead at its end. */
    fun commitInsert() {
        if (!_uiState.value.isInsertActive) return
        launchLogged {
            val clip = insertRecorder.finish()
            stopInsertScene()
            updateState { it.copy(isInsertActive = false, isInsertRecording = false) }
            if (clip == null) return@launchLogged // nothing captured

            val session = editSession
            val clipSource = FilePcmSource(clip.file)
            if (session == null || !session.insertRelative(insertAtFrame, clipSource)) {
                runCatching { clip.file.delete() }
                return@launchLogged
            }
            pendingInsertClips.add(clip.file)
            ensureClipPeakCache(clipSource)
            reloadCurrentTakePlayback(insertAtFrame + clip.frames)
        }
    }

    /** Abandons the session: releases the mic, deletes the partial clip, leaves the take untouched. */
    fun cancelInsert() {
        if (!_uiState.value.isInsertActive) return
        launchLogged {
            insertRecorder.discard()
            stopInsertScene()
            updateState { it.copy(isInsertActive = false, isInsertRecording = false) }
        }
    }

    /** Peaks for an inserted clip so the spliced region draws like the rest of the waveform. */
    private fun ensureClipPeakCache(source: PcmSource) {
        if (peakCaches.containsKey(source.id)) return
        val cache = WaveformPeakCache(source.totalFrames)
        peakCaches[source.id] = cache
        launchLogged(Dispatchers.IO) {
            runCatching { buildPeakCache(source, cache) }
            bumpTimelineGeneration()
        }
    }

    /** Drops clips that were spliced but never saved (called on teardown / after a successful save). */
    private fun discardPendingInsertClips() {
        pendingInsertClips.forEach { runCatching { it.delete() } }
        pendingInsertClips.clear()
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

        // Naming, chapter directory, take creation, the copy and the insert all live in
        // SaveAudioAsNewTake — Orature's chapter-review screen performs the same sequence. The new
        // take becomes the selected one via WorkbookRepository, once its insert has an id.
        val newTake = saveAudioAsNewTake.execute(
            workbook = wb,
            chapter = target.chapter,
            chunk = target.chunk,
            recordable = target.recordable,
            audioFile = editedAudioFile
        )
        _editedTakeSavedEvents.tryEmit(newTake.number)
    }

    private fun currentTarget(): UnitTarget? = targets.getOrNull(currentTargetIndex)

    private fun switchToTarget(index: Int, force: Boolean = false) {
        if (index !in targets.indices) return
        if (!force && _uiState.value.isPlaying) return

        currentTargetIndex = index
        val wb = workbook ?: return
        val target = targets[index]
        associatedAudio = target.recordable.audio

        audioPlayer.pause()
        clock.advancing = false
        peakCacheJob?.cancel()
        markerFrames = emptyList()
        markerLabels = emptyList()
        baseMarkers = emptyList()
        activeTake = null
        editSession = null
        selectionStartFrame = null
        selectionEndFrame = null
        editedMarkers.clear()
        bumpTimelineGeneration()   // renderTimeline becomes null until a take loads

        clock.snapTo(0L)
        updateState { it.copy(
            selectedTake = null,
            takes = emptyList(),
            currentTakeNumber = null,
            durationFrames = 0,
            sampleRate = 44100,
            markerFrames = emptyList(),
            markerLabels = emptyList(),
            durationText = "00:00:00",
            isVerseMarkerMode = false,
            versesRemaining = 0,
            error = null
        ) }

        updateTargetUi(target, wb)
        updateEditUi()
        observeTargetAudio()

        // Resolve and load source audio for this target. Disk-bound work goes off
        // the main thread; the controller's state flow drives the UI.
        launchLogged(Dispatchers.IO) {
            val available = sourceAudioController.load(wb, target.chapter, target.chunk)
            updateState { it.copy(sourceAudioAvailable = available) }
        }
    }

    fun toggleSourcePlayback() {
        sourceAudioController.togglePlayPause()
    }

    fun seekSourceToProgress(progress: Float) {
        sourceAudioController.seekToProgress(progress)
    }

    private fun updateTargetUi(target: UnitTarget, wb: Workbook) {
        updateState { it.copy(
            targetUi = TargetUiState(
                languageLabel = wb.target.language.name,
                projectLabel = wb.target.resourceMetadata.identifier.uppercase(),
                bookLabel = wb.target.title,
                chapterValue = target.chapter.sort.toString(),
                unitValue = (target.chunk?.sort ?: 0).toString()
            )
        ) }
    }

    private fun observeTargetAudio() {
        takesJob?.cancel()
        selectedJob?.cancel()

        val audio = associatedAudio ?: return
        val takeMap = linkedMapOf<Int, Take>()

        takesJob = launchLogged {
            audio.takesFlow.collect { take ->
                takeMap[take.number] = take
                val takes = takeMap.values
                    .filter { !it.isDeleted() }
                    .sortedBy { it.number }
                updateState { it.copy(takes = takes) }

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

        selectedJob = launchLogged {
            audio.selectedFlow.collect { selectedHolder ->
                val selectedTake = selectedHolder.value
                updateState { it.copy(selectedTake = selectedTake) }
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
            ensurePeakCache(take)
            val originalAudio = OratureAudioFile(take.file)
            baseMarkers = originalAudio.getMarkers().sortedBy { it.location }
            selectionStartFrame = null
            selectionEndFrame = null
            // reloadCurrentTakePlayback → refreshMarkerFrames populates editedMarkers
            // (all kinds) and the UI marker lists from baseMarkers.
            reloadCurrentTakePlayback(0)
        }.onFailure { e ->
            // loadTakeForPlayback is synchronous; getString is suspend, so resolve
            // the fallback in a coroutine.
            launchLogged {
                updateState { it.copy(error = e.message ?: getString(Res.string.err_load_take)) }
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
        editSession = WaveEditSession(FilePcmSource(take.file))
    }

    private fun buildReaderForTake(take: Take): AudioFileReader {
        val session = editSession
        return if (session != null && session.hasEdits()) {
            TimelineAudioFileReader(session.timeline())
        } else {
            // No edits: play the whole source directly (identical to the raw reader).
            TimelineAudioFileReader(AudioTimeline.ofWholeSource(FilePcmSource(take.file)))
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
        // Show all marker kinds the take carries (book/chapter/verse), remapped
        // through any active edit session, in BCV/frame order.
        val mapped = mapEditedMarkers(baseMarkers)
            .filter { it is BookMarker || it is ChapterMarker || it is VerseMarker }
            .sortedBy { it.location }
        editedMarkers.clear()
        mapped.forEach { editedMarkers.add(EditMarker(it.location, it.label, kindOf(it))) }
        publishMarkers()
    }

    private fun kindOf(marker: AudioMarker): MarkerKind = when (marker) {
        is BookMarker -> MarkerKind.BOOK
        is ChapterMarker -> MarkerKind.CHAPTER
        else -> MarkerKind.VERSE
    }

    private fun reloadCurrentTakePlayback(seekFrame: Int) {
        val take = activeTake ?: return
        audioPlayer.pause()
        clock.advancing = false

        val reader = buildReaderForTake(take)
        waveformSampleRate = reader.spec.sampleRate
        val durationFrames = reader.totalFrames
        val clampedSeek = seekFrame.coerceIn(0, durationFrames)

        audioPlayer.load(reader)
        audioPlayer.seek(clampedSeek)

        refreshMarkerFrames()
        // Derive transport + waveform center from the known seek target, not the
        // async-stale player position (matters when reloading with a non-zero seek
        // after a cut/undo/redo).
        applyTransportForFrame(clampedSeek)
        updateEditUi()
        // Every reload follows a timeline-shape change (take load, cut, undo, redo):
        // refresh the render timeline and invalidate the waveform's cached scratch.
        bumpTimelineGeneration()
    }

    // Builds (or reuses) the in-memory peak cache for the take's source file. Live
    // rendering reads ONLY this cache — the draw loop never touches disk. Progressive:
    // the waveform appears as the build scans the file. Cancelled by the next take.
    private fun ensurePeakCache(take: Take) {
        val source = FilePcmSource(take.file)
        if (peakCaches.containsKey(source.id)) return
        peakCacheJob?.cancel()
        // Drop caches for sources no longer referenced (previous takes), but KEEP any clips spliced
        // into the current timeline — their peaks are still being drawn.
        val stillReferenced = mutableSetOf(source.id)
        editSession?.timeline()?.segments?.forEach { stillReferenced.add(it.source.id) }
        peakCaches.keys.retainAll(stillReferenced)
        val cache = WaveformPeakCache(source.totalFrames)
        peakCaches[source.id] = cache
        peakCacheJob = launchLogged(Dispatchers.IO) {
            runCatching { buildPeakCache(source, cache) }
            bumpTimelineGeneration()
        }
    }

    private fun updateEditUi() {
        val duration = audioPlayer.getDurationInFrames().coerceAtLeast(1)
        val s = selectionStartFrame?.coerceIn(0, duration)
        val e = selectionEndFrame?.coerceIn(0, duration)
        // Present the selection ordered (start = the left/earlier handle, end = the
        // right/later one) so dragging one handle past the other visibly re-sorts
        // instead of the markers crossing or one vanishing.
        val lo = if (s != null && e != null) minOf(s, e) else s
        val hi = if (s != null && e != null) maxOf(s, e) else e
        val startProgress = lo?.let { it.toFloat() / duration.toFloat() }
        val endProgress = hi?.let { it.toFloat() / duration.toFloat() }
        val canCut = selectionStartFrame != null &&
            selectionEndFrame != null &&
            abs((selectionStartFrame ?: 0) - (selectionEndFrame ?: 0)) >= 2

        // Atomic update: this runs on the Main-thread ticker path while background
        // coroutines may update other fields concurrently; `.update {}` CAS-retries
        // so neither write is lost.
        updateState { it.copy(
            selectionStartProgress = startProgress,
            selectionEndProgress = endProgress,
            canCutSelection = canCut,
            canUndoEdit = editSession?.canUndo() == true,
            canRedoEdit = editSession?.canRedo() == true
        ) }
    }

    // The 60 Hz ticker + interpolation that used to live here is replaced by
    // the display position (PlaybackDisplayPosition), driven per display frame from
    // PlaybackScreen. Per-frame position lives ONLY in the clock; uiState carries just the slow-changing
    // duration fields. The time readout is a leaf composable reading the clock.

    // Sets the display clock (position) and the slow transport fields (durations)
    // from an EXPLICIT frame rather than polling the player. Use after
    // audioPlayer.seek(), whose async nature means getLocationInFrames() would still
    // report the old position. Duration is safe to read from the player (it reflects
    // the loaded reader, not the play head).
    private fun applyTransportForFrame(frame: Int) {
        val durationFrames = audioPlayer.getDurationInFrames().coerceAtLeast(0)
        val durationMs = audioPlayer.getDurationMs().coerceAtLeast(0)
        val sr = waveformSampleRate.coerceAtLeast(1)
        val clamped = frame.coerceIn(0, durationFrames)
        clock.sampleRate = sr
        clock.durationFrames = durationFrames.toLong()
        clock.snapTo(clamped.toLong())
        updateState { it.copy(
            durationFrames = durationFrames,
            sampleRate = sr,
            durationMs = durationMs,
            durationText = formatPlaybackTime(durationMs),
            currentTakeNumber = it.selectedTake?.number
        ) }
        updateEditUi()
    }

    // Refresh only the duration-side fields (position is the clock's job).
    private fun refreshTransport() {
        val durationMs = audioPlayer.getDurationMs().coerceAtLeast(0)
        val durationFrames = audioPlayer.getDurationInFrames().coerceAtLeast(0)
        clock.sampleRate = waveformSampleRate.coerceAtLeast(1)
        clock.durationFrames = durationFrames.toLong()

        updateState { it.copy(
            durationFrames = durationFrames,
            sampleRate = waveformSampleRate,
            durationMs = durationMs,
            durationText = formatPlaybackTime(durationMs),
            currentTakeNumber = it.selectedTake?.number
        ) }
        updateEditUi()
    }

    fun cleanup() {
        clock.advancing = false
        takesJob?.cancel()
        selectedJob?.cancel()
        peakCacheJob?.cancel()
        peakCaches.clear()
        sourceAudioController.release()
        audioPlayer.release()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
        // An insert session left open (or spliced-but-unsaved clips) must not leak the mic or files.
        // Synchronously, not `launchLogged { discard() }`: androidx cancels viewModelScope *before*
        // calling onCleared, so that launch never ran and the mic stayed open — see
        // [InsertRecorder.discardOnTeardown].
        if (insertRecorder.isActive) {
            insertRecorder.discardOnTeardown()
        }
        discardPendingInsertClips()
    }
}
