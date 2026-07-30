package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.device.AudioFileReader
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.domain.narration.AudioScene
import org.bibletranslationtools.otter.common.domain.narration.Narration
import org.bibletranslationtools.otter.common.domain.narration.teleprompter.NarrationStateTransition
import org.bibletranslationtools.shared.ui.playback.AudioTimeline
import org.bibletranslationtools.shared.ui.playback.PcmSource
import org.bibletranslationtools.shared.ui.playback.PlaybackDisplayClock
import org.bibletranslationtools.shared.ui.playback.WaveformPeakCache
import org.bibletranslationtools.shared.ui.playback.buildPeakCache
import kotlin.math.max
import org.bibletranslationtools.orature.ui.narration.OratureNarrationFactory
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.bibletranslationtools.otter.common.domain.narration.LoadChapterSourceText
import org.bibletranslationtools.otter.common.domain.project.OpenWorkbook
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.audio.MarkerType
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.editVerseMarkers
import org.jetbrains.compose.resources.getString
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.domain.narration.teleprompter.NarratableItem
import org.bibletranslationtools.otter.common.domain.narration.teleprompter.NarrationStateType
import org.bibletranslationtools.otter.common.domain.narration.teleprompter.TeleprompterItemState
import org.bibletranslationtools.otter.common.domain.narration.teleprompter.TeleprompterStateMachine
import org.bibletranslationtools.orature.plugins.PluginCapability
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Pixel resolution of the live-record waveform ring buffer (drawn scaled to the workspace). */
// AudioScene buffer columns. Kept wider than any real screen so the workspace draw down-samples to
// crisp 1px lines instead of up-sampling a low-res buffer into fat blocks (framesToPixels scales
// with this, so the on-screen time span is unchanged — only the resolution increases).
private const val NARRATION_WAVEFORM_WIDTH = 4096

/** Minimum spacing kept between adjacent verse markers when dragging (~0.1s), to avoid crossing. */
private const val MIN_MARKER_GAP_FRAMES = 4410

/** One cell in the chapter-selector grid (JVM: `ChapterGridItemData`). */
data class OratureChapterGridItem(
    val sort: Int,
    /** The chapter number/title shown on the button (JVM: `chapter.title`). */
    val title: String,
    val completed: Boolean,
    val selected: Boolean
)

/**
 * One row in the teleprompter (JVM: `NarratableItemModel`): a verse (or a chapter/book title
 * marker) with its narration [state] and the derived per-verse affordance flags.
 */
data class OratureVerseItem(
    val index: Int,
    /** Verse number/range (JVM: marker `label`), or the title text for a title marker. */
    val label: String,
    /** The verse text to narrate (empty for title markers or when unavailable). */
    val text: String,
    /** True for chapter/book title markers, which are not recordable verses. */
    val isTitle: Boolean,
    val state: TeleprompterItemState,
    val isPlayEnabled: Boolean,
    val isEditEnabled: Boolean,
    val isRecordAgainEnabled: Boolean
)

data class OratureNarrationUiState(
    val isLoading: Boolean = true,
    /** Raw target book title (screen formats it with the `narrationTitle` string). */
    val bookTitle: String = "",
    /** Active chapter number/title (screen formats it with the `chapterTitle` string). */
    val activeChapterTitle: String = "",
    val activeChapterSort: Int? = null,
    val chapters: List<OratureChapterGridItem> = emptyList(),
    val hasPreviousChapter: Boolean = false,
    val hasNextChapter: Boolean = false,
    /** The narration verse list (empty while a chapter's narration is still loading). */
    val verses: List<OratureVerseItem> = emptyList(),
    /** Overall narration state (JVM: `narrationStateProperty`); null before a chapter loads. */
    val narrationState: NarrationStateType? = null,
    /** True once the narration domain is ready, enabling the record/play controls. */
    val actionsEnabled: Boolean = false,
    /** Chapter playback is active (drives the transport play/pause icon). */
    val isPlaying: Boolean = false,
    /** Verse index currently playing/recording, for teleprompter highlight + auto-scroll (-1 = none). */
    val highlightedVerseIndex: Int = -1,
    /** Recorded verse markers (verse index + RELATIVE chapter-frame location) for the workspace. */
    val markerInfos: List<OratureMarkerInfo> = emptyList(),
    /** Header undo/redo enablement (JVM: hasUndo/hasRedo && not mid-record). */
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Scrub/scrollbar active (JVM: isScrollEnabled) — not recording/playing/prepending. */
    val scrollEnabled: Boolean = false,
    /** Verse markers are draggable only in a stable (non-recording/non-playing) state. */
    val markersEditable: Boolean = false,
    /** Options-menu restart enabled only once recording has started (JVM: IN_PROGRESS/FINISHED). */
    val canRestartChapter: Boolean = false,
    val error: String? = null,
    /** True while an external plugin (chapter or verse editor) is open — blocks navigation and
     *  swaps the whole screen for [org.bibletranslationtools.orature.ui.components.OraturePluginOpenedCover]. */
    val isPluginOpen: Boolean = false,
    /** Active chapter's source scripture text, joined for the plugin-opened cover. */
    val sourceText: String = "",
    val sourceLicense: String = ""
)

/**
 * A recorded verse marker for the workspace: its index in totalVerses, relative frame location, and
 * display label (verse number). [movable] is false for the first marker (JVM: index 0 can't move).
 */
data class OratureMarkerInfo(
    val verseIndex: Int,
    val location: Int,
    val label: String,
    val movable: Boolean,
    /** Per-verse "⋮" menu gating (JVM: `VerseMarkerControl.isPlayingEnabledProperty` /
     *  `isEditVerseEnabledProperty` / `isRecordAgainEnabledProperty`, bound from the matching
     *  [NarratableItem]'s `isPlayOptionEnabled`/`isEditVerseOptionEnabled`/`isRecordAgainOptionEnabled`).
     *  Only narration's marker menu (`OratureAudioWorkspace`) reads these; the other producers of
     *  this shared type (Consume/Chunking/ChapterReview/VerseMarkerEditor) don't show a menu, so
     *  their defaults are never consulted. */
    val isPlayEnabled: Boolean = true,
    val isEditEnabled: Boolean = false,
    val isRecordAgainEnabled: Boolean = true
)

/**
 * Pure mapping of the domain's verse markers + the teleprompter state machine's per-verse
 * [NarratableItem]s + the verse texts into the UI [OratureVerseItem] list. Kept side-effect-free
 * so it can be unit-tested without a live [Narration].
 */
internal fun buildVerseItems(
    markers: List<AudioMarker>,
    items: List<NarratableItem>,
    textByLabel: Map<String, String>,
    textByIndex: List<String>
): List<OratureVerseItem> = markers.mapIndexed { i, marker ->
    val item = items.getOrNull(i)
    val isTitle = marker.type == MarkerType.TITLE
    // Verses match the source scripture chunk by label (robust); title markers (book/chapter)
    // take the parallel source chunk by index, falling back to the marker's own label.
    val body = if (isTitle) {
        textByIndex.getOrNull(i)?.takeIf { it.isNotBlank() } ?: marker.label
    } else {
        textByLabel[marker.label]?.takeIf { it.isNotBlank() }
            ?: textByIndex.getOrNull(i)?.takeIf { it.isNotBlank() }
            ?: ""
    }
    OratureVerseItem(
        index = i,
        label = marker.label,
        text = body,
        isTitle = isTitle,
        state = item?.verseState ?: TeleprompterItemState.RECORD_DISABLED,
        isPlayEnabled = item?.isPlayOptionEnabled ?: false,
        isEditEnabled = item?.isEditVerseOptionEnabled ?: false,
        isRecordAgainEnabled = item?.isRecordAgainOptionEnabled ?: false
    )
}

/**
 * Drives the Orature narration page SHELL (Phase 4): loads the [org.bibletranslationtools.otter.common.data.workbook.Workbook]
 * for a clicked book, publishes it to the shared [OratureWorkbookDataStore], and exposes the
 * header state — book title, active chapter title, the chapter-selector grid, and prev/next
 * availability. Chapter selection here is fully live (JVM: `NarrationHeaderViewModel`); the
 * narration BODY (audio workspace / teleprompter / recording) arrives in Phase 5.
 *
 * The "completed" grid flag uses the lightweight [Chapter.hasSelectedAudio] proxy; Phase 5
 * replaces it with the real `ProjectCompletionStatus.getChapterNarrationProgress == 1.0`
 * (the JVM signal) once narration state is wired.
 */
class OratureNarrationViewModel(
    private val workbookDescriptorId: Int
) : ViewModel(), KoinComponent {

    private val openWorkbook: OpenWorkbook by inject()
    private val loadChapterSourceText: LoadChapterSourceText by inject()
    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val narrationFactory: OratureNarrationFactory by inject()
    private val pluginStore: org.bibletranslationtools.orature.plugins.OraturePluginStore by inject()
    private val verseMarkerEditor: OratureVerseMarkerEditor by inject()
    private val navigationLock: org.bibletranslationtools.orature.ui.OratureNavigationLock by inject()

    private val _uiState = MutableStateFlow(OratureNarrationUiState())
    val uiState: StateFlow<OratureNarrationUiState> = _uiState.asStateFlow()

    /** Fires when the built-in Verse Marker editor is ready to open (handoff populated); the screen
     *  collects this and navigates to the marker route (JVM: launching the marker plugin window). */
    private val _openVerseMarkerEditor = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openVerseMarkerEditor: kotlinx.coroutines.flow.SharedFlow<Unit> = _openVerseMarkerEditor

    /** Sorted chapters for the active workbook, cached for prev/next stepping. */
    private var chapters: List<Chapter> = emptyList()

    // ---- narration domain (rebuilt per active chapter) ----------------------------------
    private var narration: Narration? = null
    private var stateMachine: TeleprompterStateMachine? = null
    private var activeVersesDisposable: Disposable? = null
    // Source scripture text for the teleprompter: by verse label (verses) and by marker index (titles).
    private var verseTextByLabel: Map<String, String> = emptyMap()
    private var sourceTextByIndex: List<String> = emptyList()

    // Record-loop bookkeeping (JVM: the matching NarrationViewModel properties).
    private var recordingVerseIndex: Int = -1
    private var isPrependRecording: Boolean = false
    private var recordAgainVerseIndex: Int? = null
    private var prependRecordingVerseIndex: Int? = null
    private var playingVerseIndex: Int = -1
    private var micStarted: Boolean = false
    // The compiled chapter take (JVM: chapterTakeProperty). Created once every verse is recorded,
    // deleted if the chapter drops back to incomplete. Persisted+selected by the workbook repo, so
    // reopening restores it via chapter.getSelectedTake().
    private var chapterTake: Take? = null
    private var positionTickerJob: Job? = null
    private var playerEventsJob: Job? = null

    // Waveform: AudioScene composites the recorded chapter reader + the live mic take into one
    // min/max buffer per frame. A background ticker recomputes it; the workspace draws
    // scene.frameBuffer. [lastViewport] is the frame window currently shown (for marker x-pos).
    private val _isRecordingActive = MutableStateFlow(false)
    private var sceneReader: AudioFileReader? = null
    private val _audioScene = MutableStateFlow<AudioScene?>(null)
    val audioScene: StateFlow<AudioScene?> = _audioScene.asStateFlow()
    private var lastViewports: List<IntRange> = emptyList()
    private var waveformTickerJob: Job? = null
    private var volumeJob: Job? = null
    // Published (immutable) snapshot of the composite waveform + mic level. The ticker swaps the
    // reference after each full render so the Canvas never reads a half-cleared scene.frameBuffer
    // (which flickers). Reference assignment is atomic on the JVM.
    private var waveformFront: FloatArray = FloatArray(NARRATION_WAVEFORM_WIDTH * 2)
    private var volumeLevel: Float = 0f

    /** The frame window(s) the waveform is currently showing (1 normally, 2 in the re-record split view). */
    fun currentViewports(): List<IntRange> = lastViewports

    /**
     * The verse index the re-record/prepend split pivots on (JVM: recordAgainVerseIndex ?:
     * prependRecordingVerseIndex), or null in the normal single-viewport case. Markers at index
     * <= this go in the left viewport, > this in the right.
     */
    fun currentSplitPivot(): Int? {
        val ctx = stateMachine?.getNarrationContext()
        return when {
            ctx == NarrationStateType.RECORDING_AGAIN || ctx == NarrationStateType.RECORDING_AGAIN_PAUSED ->
                recordAgainVerseIndex
            isPrependRecording -> prependRecordingVerseIndex
            else -> null
        }
    }

    /** The latest composited waveform snapshot (min/max pairs). */
    fun currentWaveform(): FloatArray = waveformFront

    /** The latest mic level 0..1 (0 unless recording). */
    fun currentVolume(): Float = volumeLevel

    // Playhead frame + total chapter frames, republished each waveform tick, for the scrollbar +
    // scrub-drag (JVM: audioPositionProperty / totalAudioSizeProperty).
    private var audioPositionFrames: Int = 0
    private var totalAudioFrames: Int = 0
    // The scrollbar thumb (size = window/total, offset = position/total). During RECORDING the write
    // head is tracked by the waveform ticker (audioPositionFrames); during playback/idle the position
    // is the smooth display clock and the total must come straight from the chapter (the ticker only
    // updates totalAudioFrames while an AudioScene render is running, so it stays 0 during pure
    // playback — which collapsed the thumb to full width and made the scrollbar look absent).
    fun currentAudioPosition(): Int = if (isRecordingView()) audioPositionFrames else clock.displayFrame.toInt()
    fun currentTotalFrames(): Int = narration?.getTotalFrames() ?: totalAudioFrames

    // ---- frame-stable PLAYBACK renderer (shared engine, like the translation surfaces) ----------
    // During playback/idle the workspace draws the chapter from this immutable peak cache via
    // AudioTimeline.fillWindow + the display clock (absolute-frame-grid columns → no re-bin/crawl,
    // smooth per-frame scroll). During RECORDING it falls back to the live AudioScene. The cache is
    // rebuilt off-thread whenever the active verses change (record/re-record/edit finalize).
    private var timeline: AudioTimeline? = null
    private var peakCache: WaveformPeakCache? = null
    private var peakSource: PcmSource? = null
    private var peakBuildJob: Job? = null
    private var peakRevision: Int = 0
    // Set when play-all runs to the end (Complete). Consumed by the next onPlayAll so a replay
    // snaps the clock to 0 (audio rewinds), instead of the stale end position. Cleared by any
    // explicit position change (seek / play-verse) so mid-chapter resume isn't hijacked.
    private var playbackReachedEnd = false
    val clock = PlaybackDisplayClock(
        positionSource = { narration?.getLocationInFrames()?.toLong() ?: 0L },
        positionReliable = { narration?.getPlayer()?.isPositionReliable() ?: false }
    )
    fun currentTimeline(): AudioTimeline? = timeline
    fun peakCacheFor(source: PcmSource): WaveformPeakCache? =
        if (source.id == peakSource?.id) peakCache else null
    fun waveformSampleRate(): Int = DEFAULT_SAMPLE_RATE

    /** True while a recording is in progress/paused — the workspace shows the live AudioScene then,
     *  not the (stale, being-rebuilt) peak cache. */
    fun isRecordingView(): Boolean = when (stateMachine?.getNarrationContext()) {
        NarrationStateType.RECORDING,
        NarrationStateType.RECORDING_AGAIN,
        NarrationStateType.RECORDING_PAUSED,
        NarrationStateType.RECORDING_AGAIN_PAUSED -> true
        else -> false
    }

    /** (Re)build the chapter peak cache off-thread. Cheap enough to run on every active-verse change;
     *  a new revisioned [PcmSource] id makes any in-flight draw fall back cleanly until it completes. */
    private fun buildChapterPeakCache() {
        val n = narration ?: return
        peakBuildJob?.cancel()
        val total = n.getDurationInFrames()
        if (total <= 0) {
            timeline = null; peakCache = null; peakSource = null
            return
        }
        val source = NarrationChapterPcmSource(n, ++peakRevision)
        val cache = WaveformPeakCache(total)
        timeline = AudioTimeline.ofWholeSource(source)
        peakCache = cache
        peakSource = source
        clock.durationFrames = total.toLong()
        peakBuildJob = launchLogged(Dispatchers.IO) {
            runCatching { buildPeakCache(source, cache) }
                .onFailure { System.err.println("[narration] peak cache build failed: $it") }
        }
    }

    init {
        load()
    }

    private fun load() {
        launchLogged {
            _uiState.value = OratureNarrationUiState(isLoading = true)
            try {
                // Descriptor lookup, workbook resolution, chapter ordering and the completion
                // snapshot all live in OpenWorkbook, which does its own IO dispatch.
                val loaded = openWorkbook.openWithChapters(workbookDescriptorId)

                // open() scaffolds the on-disk project files (RC manifest, source copy, takes/chunks
                // files) — file I/O, so keep it off the main thread.
                withContext(Dispatchers.IO) { workbookDataStore.open(loaded.workbook, loaded.mode) }
                chapters = loaded.chapters

                // Restore the last-viewed chapter for this workbook, else the first (JVM behavior).
                val restoredSort = workbookDataStore.lastChapterSort(workbookDescriptorId)
                val active = chapters.firstOrNull { it.sort == restoredSort }
                    ?: chapters.firstOrNull()

                if (active != null) {
                    workbookDataStore.setActiveChapter(active, workbookDescriptorId)
                }

                _uiState.value = OratureNarrationUiState(
                    isLoading = false,
                    bookTitle = loaded.workbook.target.title.ifEmpty { loaded.workbook.target.slug.uppercase() },
                    activeChapterTitle = active?.title.orEmpty(),
                    activeChapterSort = active?.sort,
                    chapters = buildGrid(active?.sort, loaded.completedByChapterSort),
                    hasPreviousChapter = hasNeighbor(active?.sort, step = -1),
                    hasNextChapter = hasNeighbor(active?.sort, step = +1)
                )

                if (active != null) initializeNarration(active)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading the narration screen", e)
                _uiState.value = OratureNarrationUiState(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    /** Select a chapter by its sort number (JVM: `NavigateChapterEvent`). */
    fun selectChapter(sort: Int) {
        if (_uiState.value.isPluginOpen) return
        val chapter = chapters.firstOrNull { it.sort == sort } ?: return
        workbookDataStore.setActiveChapter(chapter, workbookDescriptorId)
        val current = _uiState.value
        _uiState.value = current.copy(
            activeChapterTitle = chapter.title,
            activeChapterSort = chapter.sort,
            chapters = current.chapters.map { it.copy(selected = it.sort == sort) },
            hasPreviousChapter = hasNeighbor(sort, step = -1),
            hasNextChapter = hasNeighbor(sort, step = +1),
            // Clear the previous chapter's verses until the new chapter's narration loads.
            verses = emptyList(),
            narrationState = null
        )
        launchLogged { initializeNarration(chapter) }
    }

    fun selectPreviousChapter() = stepChapter(step = -1)

    fun selectNextChapter() = stepChapter(step = +1)

    private fun stepChapter(step: Int) {
        val activeSort = _uiState.value.activeChapterSort ?: return
        val index = chapters.indexOfFirst { it.sort == activeSort }
        chapters.getOrNull(index + step)?.let { selectChapter(it.sort) }
    }

    private fun hasNeighbor(activeSort: Int?, step: Int): Boolean {
        if (activeSort == null) return false
        val index = chapters.indexOfFirst { it.sort == activeSort }
        return index >= 0 && chapters.getOrNull(index + step) != null
    }

    private fun buildGrid(activeSort: Int?, completed: Map<Int, Boolean>): List<OratureChapterGridItem> =
        chapters.map { chapter ->
            OratureChapterGridItem(
                sort = chapter.sort,
                title = chapter.title,
                completed = completed[chapter.sort] ?: false,
                selected = chapter.sort == activeSort
            )
        }

    // ---- narration domain ---------------------------------------------------------------

    /**
     * (Re)build the [Narration] for [chapter]: create + initialize it, load the verse texts,
     * seed the teleprompter state machine from the verses' recording status, and subscribe to
     * active-verse updates. Mirrors the JVM `initializeNarration` + `resetNarratableList`.
     */
    private suspend fun initializeNarration(chapter: Chapter) {
        // Tear down the previous chapter's narration first.
        stopPositionTicker()
        waveformTickerJob?.cancel()
        waveformTickerJob = null
        volumeJob?.cancel()
        volumeJob = null
        playerEventsJob?.cancel()
        playerEventsJob = null
        _audioScene.value?.close()
        _audioScene.value = null
        runCatching { sceneReader?.release() }
        sceneReader = null
        peakBuildJob?.cancel()
        peakBuildJob = null
        timeline = null
        peakCache = null
        peakSource = null
        clock.advancing = false
        lastViewports = emptyList()
        waveformFront = FloatArray(NARRATION_WAVEFORM_WIDTH * 2)
        volumeLevel = 0f
        _isRecordingActive.value = false
        activeVersesDisposable?.dispose()
        activeVersesDisposable = null
        narration?.close()
        narration = null
        stateMachine = null
        recordingVerseIndex = -1
        playingVerseIndex = -1
        isPrependRecording = false
        recordAgainVerseIndex = null
        prependRecordingVerseIndex = null
        micStarted = false
        chapterTake = null
        _uiState.value = _uiState.value.copy(
            actionsEnabled = false, isPlaying = false, highlightedVerseIndex = -1,
            markerInfos = emptyList()
        )

        try {
            val workbook = workbookDataStore.workbook
            val prepared = withContext(Dispatchers.IO) {
                val n = narrationFactory.create(workbook, chapter, viewModelScope)
                n.initialize().await()
                // Teleprompter text comes from the SOURCE scripture (the target project has no
                // text yet) — LoadChapterSourceText matches the source book's chapter by sort.
                // Missing source text is not fatal: the teleprompter renders empty. It used to be
                // swallowed silently; now it is logged, matching how the rest of this VM reports
                // failures.
                val sourceText = runCatching { loadChapterSourceText.execute(workbook, chapter.sort) }
                    .getOrElse { e ->
                        logFailure("loading the chapter source text", e)
                        LoadChapterSourceText.ChapterSourceText.EMPTY
                    }
                Prepared(n, sourceText)
            }
            narration = prepared.narration
            chapterTake = chapter.getSelectedTake()
            verseTextByLabel = prepared.sourceText.byVerseLabel
            sourceTextByIndex = prepared.sourceText.inOrder
            _uiState.value = _uiState.value.copy(
                sourceText = prepared.sourceText.inOrder.joinToString("\n"),
                sourceLicense = runCatching { workbook.source.resourceMetadata.license }.getOrDefault("")
            )

            val sm = TeleprompterStateMachine(prepared.narration.totalVerses)
            sm.initialize(prepared.narration.versesWithRecordings())
            stateMachine = sm

            // Mic runs for the whole narration session; the writer only captures while a verse
            // is recording. Guard so a missing input device doesn't crash the page.
            runCatching { prepared.narration.startMicrophone() }
                .onFailure { System.err.println("[narration] startMicrophone failed: $it") }
            micStarted = true

            // Waveform: composite the recorded chapter audio (its own reader connection) with the
            // live mic take. The reader must be opened before the scene reads from it.
            val reader = prepared.narration.audioReader
            runCatching { reader.open() }
            sceneReader = reader
            _audioScene.value = AudioScene(
                reader,
                prepared.narration.getRecorderAudioStream(),
                _isRecordingActive,
                NARRATION_WAVEFORM_WIDTH,
                secondsOnScreen = 10,
                recordingSampleRate = DEFAULT_SAMPLE_RATE
            )
            startWaveformTicker()
            buildChapterPeakCache()

            // Live mic level for the volume bar (JVM: VolumeBar over the recorder stream) — the
            // max sample of each incoming chunk, so it rises AND falls with the voice.
            volumeJob = launchLogged(Dispatchers.Default) {
                prepared.narration.getRecorderAudioStream().collect { bytes -> volumeLevel = micLevel(bytes) }
            }

            // Auto-pause when the player reaches the end (JVM: COMPLETE listener).
            playerEventsJob = launchLogged {
                prepared.narration.getPlayer().events.collect { event ->
                    if (event is AudioPlayerEvent.Complete) {
                        System.err.println("[narr-diag] COMPLETE loc=${prepared.narration.getLocationInFrames()} dur=${prepared.narration.getDurationInFrames()} clock=${clock.displayFrame} playingVerse=$playingVerseIndex")
                        prepared.narration.onPlaybackFinished()
                        playbackReachedEnd = true
                        clock.advancing = false
                        // Land the playhead on the CANONICAL end. Do NOT trust the player's completion
                        // position: on some platforms the sink stops reporting ~one audio-buffer short
                        // (~19 ms), leaving the waveform shy of its drawn end. Play-all ends at the
                        // chapter end (getDurationInFrames — the audio-read trace proved every frame
                        // reaches the sink); a finished single verse ends at that verse's chapter-space
                        // end (start of the next verse, else chapter end) so its waveform reaches the
                        // verse boundary instead of stopping short.
                        if (playingVerseIndex < 0) {
                            clock.snapTo(clock.durationFrames)
                        } else {
                            clock.snapTo(verseEndFrame(playingVerseIndex).toLong())
                        }
                        stopPositionTicker()
                        performTransition(NarrationStateTransition.PAUSE_AUDIO_PLAYBACK, playingVerseIndex.takeIf { it >= 0 })
                        _uiState.value = _uiState.value.copy(isPlaying = false, highlightedVerseIndex = -1)
                    }
                }
            }

            // The domain re-emits active verses after a record/finalize; refresh markers + verses
            // (the record transitions already advanced the state machine).
            activeVersesDisposable = prepared.narration.onActiveVersesUpdated
                .subscribe({
                    launchLogged {
                        refreshVerses(); updateMarkers(); syncChapterTake()
                        // The chapter audio changed (new/re-recorded/edited verse) — rebuild the
                        // frame-stable playback cache so the next play reflects it.
                        buildChapterPeakCache()
                    }
                }, { })

            _uiState.value = _uiState.value.copy(actionsEnabled = true)
            refreshVerses()
            updateMarkers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure("initializing narration", e)
            _uiState.value = _uiState.value.copy(verses = emptyList(), narrationState = null, actionsEnabled = false)
        }
    }

    // ---- teleprompter state publishing --------------------------------------------------

    /** Apply a state-machine transition and publish the resulting verse states. */
    private fun performTransition(transition: NarrationStateTransition, index: Int? = null) {
        val sm = stateMachine ?: return
        val items = try {
            sm.transition(transition, index)
        } catch (e: Exception) {
            logFailure("narration transition $transition@$index", e)
            sm.getVerseItemStates()
        }
        publishVerses(items)
    }

    /** Re-initialize the state machine from the domain's recording status (JVM: resetNarratableList). */
    private fun resetNarratableList() {
        val n = narration ?: return
        stateMachine?.initialize(n.versesWithRecordings())
        refreshVerses()
        updateMarkers()
    }

    private fun refreshVerses() {
        val sm = stateMachine ?: return
        publishVerses(sm.getVerseItemStates())
    }

    private fun publishVerses(items: List<NarratableItem>) {
        val n = narration ?: return
        val sm = stateMachine ?: return
        val ctx = sm.getNarrationContext()
        // Gate the live-record waveform: it accumulates mic input only while recording.
        _isRecordingActive.value =
            ctx == NarrationStateType.RECORDING || ctx == NarrationStateType.RECORDING_AGAIN
        // Undo/redo enabled only when the history is non-empty AND we're not mid-record
        // (JVM: hasUndo/hasRedo && !(isRecording || isRecordingAgainPaused)).
        val recordingLike = ctx == NarrationStateType.RECORDING ||
            ctx == NarrationStateType.RECORDING_AGAIN ||
            ctx == NarrationStateType.RECORDING_AGAIN_PAUSED
        // Scrub/scrollbar enabled (JVM isScrollEnabled): not recording/playing, not prepending.
        val scrollEnabled = when (ctx) {
            NarrationStateType.RECORDING, NarrationStateType.RECORDING_AGAIN,
            NarrationStateType.RECORDING_AGAIN_PAUSED, NarrationStateType.PLAYING -> false
            else -> true
        } && !isPrependRecording
        // Markers draggable when scroll is enabled AND not paused mid-record (JVM: marker
        // dragTarget mouseTransparent only during RECORDING_PAUSED). Must stay true through
        // MOVING_MARKER so an in-progress drag isn't cancelled by the modifier flipping off.
        val markersEditable = scrollEnabled && ctx != NarrationStateType.RECORDING_PAUSED
        _uiState.value = _uiState.value.copy(
            verses = buildVerseItems(n.totalVerses, items, verseTextByLabel, sourceTextByIndex),
            narrationState = ctx,
            canUndo = n.hasUndo() && !recordingLike,
            canRedo = n.hasRedo() && !recordingLike,
            scrollEnabled = scrollEnabled,
            markersEditable = markersEditable,
            // Restart whenever anything is recorded and we're not mid-take (more permissive than
            // JVM's IN_PROGRESS/FINISHED-only, which left partial-but-paused chapters un-restartable).
            canRestartChapter = n.activeVerses.isNotEmpty() &&
                ctx != NarrationStateType.RECORDING &&
                ctx != NarrationStateType.RECORDING_AGAIN
        )
    }

    private fun updateMarkers() {
        val n = narration ?: return
        val total = n.totalVerses
        val verseItems = _uiState.value.verses
        // Recorded verse markers with their totalVerses index (for the split-view assignment)
        // and relative chapter-frame location.
        _uiState.value = _uiState.value.copy(
            markerInfos = n.activeVerses.map { m ->
                val idx = total.indexOfFirst { it.formattedLabel == m.formattedLabel }
                val item = verseItems.getOrNull(idx)
                // First marker (index 0) is the chapter/verse-1 anchor and can't be dragged (JVM).
                OratureMarkerInfo(
                    verseIndex = idx,
                    location = m.location,
                    label = m.label,
                    movable = idx > 0,
                    isPlayEnabled = item?.isPlayEnabled ?: true,
                    isEditEnabled = item?.isEditEnabled ?: false,
                    isRecordAgainEnabled = item?.isRecordAgainEnabled ?: true
                )
            }
        )
    }

    /**
     * Create/refresh or drop the compiled chapter take as the chapter crosses the "all verses
     * recorded" line (JVM: createPotentiallyFinishedChapterTake). createChapterTake bounces the
     * active verses into a WAV and inserts+selects it (persisted by the workbook repo); deleting
     * soft-deletes it when the chapter is no longer complete. Skipped mid-record.
     */
    private fun syncChapterTake() {
        val n = narration ?: return
        val ctx = stateMachine?.getNarrationContext()
        if (ctx == NarrationStateType.RECORDING || ctx == NarrationStateType.RECORDING_AGAIN) return

        val allRecorded = n.activeVerses.isNotEmpty() && n.activeVerses.size == n.totalVerses.size
        val existing = chapterTake
        if (allRecorded && (existing == null || existing.isDeleted())) {
            launchLogged(Dispatchers.IO) {
                runCatching { n.createChapterTake().await() }
                    .onSuccess { chapterTake = it }
                    .onFailure { System.err.println("[narration] createChapterTake failed: $it") }
            }
        } else if (existing != null && !allRecorded) {
            n.deleteChapterTake()
            chapterTake = null
        }
    }

    // ---- waveform (AudioScene composite of recorded + live) ------------------------------

    /** Recompute the composited waveform (into scene.frameBuffer) and the current viewport. */
    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = launchLogged(Dispatchers.Default) {
            while (isActive) {
                val scene = _audioScene.value
                val n = narration
                if (scene != null && n != null) {
                    runCatching {
                        // Normal recording appends a NEW verse at the end, so the write head is
                        // getTotalFrames (readerEnd + uncommitted) — getLocationInFrames lags it
                        // (the player position doesn't track the write head), overflowing the
                        // AudioScene join. Re-record replaces a verse in place, so its head is the
                        // RELATIVE location (getLocationInFrames, seeked to the verse). Playback
                        // follows the playhead.
                        val ctx = stateMachine?.getNarrationContext()
                        val location = if (ctx == NarrationStateType.RECORDING) n.getTotalFrames()
                        else n.getLocationInFrames()
                        audioPositionFrames = n.getLocationInFrames()
                        totalAudioFrames = n.getTotalFrames()
                        // During re-record / prepend, render the split view (old audio + new take);
                        // otherwise the normal composite (JVM: selectRenderer).
                        val (reRecordLoc, nextVerseLoc) = selectRenderer()
                        val (buffer, viewports) =
                            scene.getNarrationDrawable(location, reRecordLoc, nextVerseLoc)
                        // Publish a stable snapshot (scene.frameBuffer is cleared + refilled each
                        // render; the Canvas must never read it mid-update).
                        waveformFront = buffer.copyOf()
                        lastViewports = viewports
                    }.onFailure { System.err.println("[narration] waveform render failed: $it") }
                }
                delay(33) // ~30 fps; the workspace redraws the published snapshot every display frame
            }
        }
    }

    /**
     * The re-record/prepend split-view locations (JVM: selectRenderer). Returns (reRecordLoc,
     * nextVerseLoc); both null for a normal composite. Verses match by formattedLabel to avoid
     * the relative-location mismatch / label collision.
     */
    private fun selectRenderer(): Pair<Int?, Int?> {
        val n = narration ?: return null to null
        val total = n.totalVerses
        val recorded = n.activeVerses
        val ctx = stateMachine?.getNarrationContext()
        if (isPrependRecording && recordingVerseIndex in total.indices) {
            val current = total[recordingVerseIndex]
            recorded.find { it.sort > current.sort }?.let { nextActive ->
                return current.location to nextActive.location
            }
        } else if (ctx == NarrationStateType.RECORDING_AGAIN || ctx == NarrationStateType.RECORDING_AGAIN_PAUSED) {
            if (recordingVerseIndex in total.indices) {
                val nextLoc = total.getOrNull(recordingVerseIndex + 1)?.let { marker ->
                    if (recorded.any { it.formattedLabel == marker.formattedLabel }) marker.location else null
                }
                return total[recordingVerseIndex].location to nextLoc
            }
        }
        return null to null
    }

    /** Max absolute 16-bit LE sample of a mic chunk, normalized 0..1 (JVM VolumeBar's level). */
    private fun micLevel(bytes: ByteArray): Float {
        var maxAbs = 0
        var i = 0
        while (i + 1 < bytes.size) {
            val sample = (bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF) // signed LE 16-bit
            val v = kotlin.math.abs(sample)
            if (v > maxAbs) maxAbs = v
            i += 2
        }
        return (maxAbs / 32767f).coerceIn(0f, 1f)
    }

    // ---- record loop (JVM: NarrationViewModel record/next/save/recordAgain) --------------

    fun onRecord(index: Int) {
        // If an external recorder is configured, record this verse in it (JVM: record() →
        // openInAudioPlugin when a RECORDER plugin is selected) — works on an empty chapter too,
        // since getSectionAsFile creates a temp file for an unrecorded verse.
        pluginFor(record = true)?.let { editVerseWithPlugin(index, it); return }
        val n = narration ?: return
        _audioScene.value?.clear()
        n.onNewVerse(index)
        recordingVerseIndex = index
        isPrependRecording = n.activeVerses.any { it.sort > n.totalVerses[index].sort }
        prependRecordingVerseIndex = if (isPrependRecording) index else null
        performTransition(NarrationStateTransition.RECORD, index)
    }

    fun onNext(index: Int) {
        val n = narration ?: return
        _audioScene.value?.clear()
        val markers = n.totalVerses
        val recorded = n.activeVerses
        val nextIndex = markers.indexOfFirst { it.sort > markers[index].sort && it !in recorded }
        when (stateMachine?.getNarrationContext()) {
            NarrationStateType.RECORDING -> {
                n.finalizeVerse(max(index, 0))
                if (nextIndex >= 0) n.onNewVerse(nextIndex)
                recordingVerseIndex = nextIndex
                isPrependRecording = nextIndex >= 0 && n.activeVerses.any { it.sort > markers[nextIndex].sort }
            }
            NarrationStateType.RECORDING_PAUSED -> {
                recordingVerseIndex = -1
                isPrependRecording = false
            }
            else -> {}
        }
        prependRecordingVerseIndex = if (isPrependRecording && nextIndex >= 0) nextIndex else null
        performTransition(NarrationStateTransition.NEXT, index)
    }

    fun onSave(index: Int) {
        val n = narration ?: return
        stopPlayer()
        _audioScene.value?.clear()
        n.onSaveRecording(index)
        isPrependRecording = false
        // JVM START_SAVE cleanup (handleNarrationEvent): clear the re-record/recording indices.
        recordAgainVerseIndex = null
        recordingVerseIndex = -1
        performTransition(NarrationStateTransition.START_SAVE, index)
        // Orature does NOT complete the save synchronously. START_SAVE marks the verse recorded
        // (→ IN_PROGRESS if the chapter still has gaps). FINISH_SAVE is deferred to the async
        // take-modifier completion and fires ONLY when the whole chapter just became complete
        // (state == MODIFYING_AUDIO_FILE → FINISHED). We run take-modify synchronously, so mirror
        // that single case here; otherwise the save is already done at START_SAVE. Calling
        // FINISH_SAVE from IN_PROGRESS (a mid-chapter re-record save) is an illegal transition.
        if (stateMachine?.getNarrationContext() == NarrationStateType.MODIFYING_AUDIO_FILE) {
            performTransition(NarrationStateTransition.FINISH_SAVE)
        }
        updateMarkers()
    }

    /** Header Undo (JVM: undo() → resetNarratableList + clear live-record data). */
    fun onUndo() {
        val n = narration ?: return
        stopPlayer()
        n.undo()
        _audioScene.value?.clear()
        resetNarratableList()
    }

    /** Header Redo (JVM: redo() → resetNarratableList). */
    fun onRedo() {
        val n = narration ?: return
        stopPlayer()
        n.redo()
        _audioScene.value?.clear()
        resetNarratableList()
    }

    /**
     * Restart the chapter (JVM: restartChapter → onResetAll + resetNarratableList). Clears every
     * recorded verse back to the begin-recording state; syncChapterTake then soft-deletes the now
     * orphaned chapter take.
     */
    fun onRestartChapter() {
        val n = narration ?: return
        stopPlayer()
        n.onResetAll()
        _audioScene.value?.clear()
        resetNarratableList()
        syncChapterTake()
    }

    /** Verse-marker drag begin (JVM: startMoveMarker → MOVING_MARKER). */
    fun onStartMoveMarker(verseIndex: Int) {
        stopPlayer()
        performTransition(NarrationStateTransition.MOVING_MARKER, verseIndex)
    }

    /**
     * Verse-marker drag end (JVM: finishMoveMarker → onVerseMarkerMoved + PLACE_MARKER). [deltaFrames]
     * is the signed frame shift (right = later). PLACE_MARKER_WHILE_MODIFYING_AUDIO if a take edit is
     * in flight, else PLACE_MARKER.
     */
    fun onFinishMoveMarker(verseIndex: Int, deltaFrames: Int) {
        val n = narration ?: return
        // Clamp so a marker can't cross (or touch) its neighbors — the domain corrupts verse
        // boundaries otherwise (JVM enforces this with verseBoundaries during the drag).
        val sorted = n.activeVerses.sortedBy { it.location }
        val myLabel = n.totalVerses.getOrNull(verseIndex)?.formattedLabel
        val i = sorted.indexOfFirst { it.formattedLabel == myLabel }
        val clampedDelta = if (i >= 0) {
            val cur = sorted[i].location
            val lower = (if (i > 0) sorted[i - 1].location + MIN_MARKER_GAP_FRAMES else 0)
            val upper = (if (i < sorted.lastIndex) sorted[i + 1].location - MIN_MARKER_GAP_FRAMES
                else n.getTotalFrames())
            (cur + deltaFrames).coerceIn(minOf(lower, upper), maxOf(lower, upper)) - cur
        } else deltaFrames
        if (clampedDelta != 0) n.onVerseMarkerMoved(verseIndex, clampedDelta)
        val modifying = stateMachine?.getNarrationContext() == NarrationStateType.MODIFYING_AUDIO_FILE
        performTransition(
            if (modifying) NarrationStateTransition.PLACE_MARKER_WHILE_MODIFYING_AUDIO
            else NarrationStateTransition.PLACE_MARKER,
            verseIndex
        )
        updateMarkers()
    }

    fun onRecordAgain(index: Int) {
        // If an external recorder is configured, re-record the verse in it (JVM: recordAgain →
        // openInAudioPlugin when a RECORDER plugin is selected); otherwise capture natively.
        pluginFor(record = true)?.let { editVerseWithPlugin(index, it); return }
        val n = narration ?: return
        stopPlayer()
        _audioScene.value?.clear()
        n.onRecordAgain(index)
        recordingVerseIndex = index
        recordAgainVerseIndex = index
        performTransition(NarrationStateTransition.RECORD_AGAIN, index)
    }

    /** The configured recorder/editor plugin, if external plugins are available (desktop + selected). */
    private fun pluginFor(record: Boolean): org.bibletranslationtools.orature.plugins.OratureExternalPlugin? =
        pluginStore.selected(if (record) PluginCapability.RECORD else PluginCapability.EDIT)

    /** True when a verse can be opened in a configured external editor (drives the teleprompter Edit button). */
    fun editorConfigured(): Boolean = pluginFor(record = false) != null

    /** Open a recorded verse in the configured external editor (JVM: openInAudioPlugin). */
    fun editVerseExternally(index: Int) {
        pluginFor(record = false)?.let { editVerseWithPlugin(index, it) }
    }

    /** Import an existing audio file to replace one verse (JVM: `importVerseAudio` →
     *  `narration.onEditVerse`) — the same splice-back call [editVerseWithPlugin] uses, just fed a
     *  user-picked file instead of a plugin-edited one. JVM's importVerseAudio doesn't stop playback
     *  or take a navigation lock (unlike the plugin path), so this doesn't either. */
    fun importVerseAudio(index: Int, file: java.io.File) {
        val n = narration ?: return
        launchLogged {
            try {
                withContext(Dispatchers.IO) { n.onEditVerse(index, file).blockingAwait() }
                refreshVerses()
                resetNarratableList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("importing verse audio", e)
            }
        }
    }

    private fun markerPlugin(): org.bibletranslationtools.orature.plugins.OratureExternalPlugin? =
        pluginStore.selected(PluginCapability.MARK)

    /** True when an editor / marker plugin is configured — drives kebab item visibility. Always
     *  enabled once shown: the chapter take is compiled on demand (JVM: Open Chapter In has no
     *  enableWhen; processChapterWithPlugin compiles via createChapterTakeWithAudio if needed). */
    fun editorConfiguredForChapter(): Boolean = pluginFor(record = false) != null

    /** Edit Verse Markers is always available now: the built-in marker editor is in-app (works on
     *  every platform), and a configured external MARKER plugin augments it (desktop only). */
    fun markerConfigured(): Boolean = true

    /** Open the chapter take in the external editor, then reload (JVM: processChapterWithPlugin). */
    fun openChapterInEditor() = launchChapterPlugin(pluginFor(record = false))

    /**
     * Edit verse markers on the chapter take. If an external MARKER plugin is selected, launch it
     * (JVM: MARKER plugin); otherwise open the built-in Verse Marker editor by compiling/reusing the
     * chapter take, populating the handoff, and signaling the screen to navigate.
     */
    fun editVerseMarkers() {
        markerPlugin()?.let { launchChapterPlugin(it); return }
        openBuiltInVerseMarkerEditor()
    }

    private fun openBuiltInVerseMarkerEditor() {
        val n = narration ?: return
        stopPlayer()
        _audioScene.value?.clear()
        launchLogged {
            try {
                // Reuse the compiled chapter take, or compile one from what's recorded (JVM:
                // chapterTakeProperty ?: createChapterTakeWithAudio) — same as launchChapterPlugin.
                val take = chapterTake ?: withContext(Dispatchers.IO) {
                    runCatching { n.createChapterTakeWithAudio().await() }.getOrNull()
                }
                if (take == null) return@launchLogged // nothing recorded yet to mark
                chapterTake = take

                val wb = workbookDataStore.activeWorkbook.value
                val chap = workbookDataStore.activeChapter.value
                val actionTitle = getString(Res.string.editVerseMarkers)
                val contentTitle = listOfNotNull(wb?.target?.title, chap?.title).joinToString(" ")
                // Source-text rows for the left panel, indexed by verse (CONTENT-marker) position so
                // the editor's highlightedIndex lines up (title markers are excluded, they sort first).
                val sourceText = n.totalVerses
                    .filter { it.type != MarkerType.TITLE }
                    .mapIndexed { i, m -> OratureVerseText(i, m.label, verseTextByLabel[m.label] ?: "") }

                verseMarkerEditor.open(
                    OratureVerseMarkerEditor.Request(
                        takeFile = take.file,
                        reservedMarkers = n.totalVerses,
                        actionTitle = actionTitle,
                        contentTitle = contentTitle,
                        sourceText = sourceText,
                        onSaved = { reloadAfterMarkerEdit() }
                    )
                )
                _openVerseMarkerEditor.emit(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("opening the built-in verse marker editor", e)
            }
        }
    }

    /** Reload the chapter from the edited take so narration reflects the new markers
     *  (JVM: onChapterReturnFromPlugin → loadFromSelectedChapterFile). */
    private suspend fun reloadAfterMarkerEdit() {
        val n = narration ?: return
        withContext(Dispatchers.IO) { runCatching { n.loadFromSelectedChapterFile().blockingAwait() } }
        refreshVerses()
        resetNarratableList()
    }

    private fun launchChapterPlugin(plugin: org.bibletranslationtools.orature.plugins.OratureExternalPlugin?) {
        plugin ?: return
        if (_uiState.value.isPluginOpen) return
        val n = narration ?: return
        stopPlayer()
        _audioScene.value?.clear()
        launchLogged {
            try {
                // Reuse the compiled chapter take, or compile one on the fly from what's recorded
                // (JVM: chapterTakeProperty ?: narration.createChapterTakeWithAudio()).
                val take = chapterTake ?: withContext(Dispatchers.IO) {
                    runCatching { n.createChapterTakeWithAudio().await() }.getOrNull()
                }
                if (take == null) return@launchLogged // nothing recorded yet to compile
                chapterTake = take
                beginPluginOpen()
                org.bibletranslationtools.orature.plugins.launchPlugin(plugin, take.file, narrationPluginParams(0))
                endPluginOpen()
                withContext(Dispatchers.IO) { runCatching { n.loadFromSelectedChapterFile().blockingAwait() } }
                refreshVerses()
                resetNarratableList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("launching the chapter plugin", e)
                endPluginOpen()
                System.err.println("Chapter external plugin failed: $e")
            }
        }
    }

    /** Lock navigation + swap in the plugin-opened cover (JVM: PluginOpenedEvent/shouldBlockWindowCloseRequest).
     *  Narration has no source-audio-player concept, so only the lock/nav-block side applies here. */
    private fun beginPluginOpen() {
        _uiState.value = _uiState.value.copy(isPluginOpen = true)
        navigationLock.lock()
    }

    private fun endPluginOpen() {
        _uiState.value = _uiState.value.copy(isPluginOpen = false)
        navigationLock.unlock()
    }

    /** Import an existing audio file as the chapter narration (JVM: onImportChapterAudio). */
    fun onImportChapterAudio(path: String) {
        val n = narration ?: return
        launchLogged {
            try {
                withContext(Dispatchers.IO) { n.importChapterAudioFile(java.io.File(path)).blockingAwait() }
                refreshVerses()
                resetNarratableList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("importing chapter audio", e)
            }
        }
    }

    /**
     * Extract a verse to a temp file, launch [plugin] on it, splice the result back, and refresh
     * (JVM: getSectionAsFile → processWithEditor → onEditVerse + onChapterReturnFromPlugin).
     */
    private fun editVerseWithPlugin(index: Int, plugin: org.bibletranslationtools.orature.plugins.OratureExternalPlugin) {
        if (_uiState.value.isPluginOpen) return
        val n = narration ?: return
        stopPlayer()
        _audioScene.value?.clear()
        launchLogged {
            try {
                val file = withContext(Dispatchers.IO) { n.getSectionAsFile(index) }
                beginPluginOpen()
                org.bibletranslationtools.orature.plugins.launchPlugin(plugin, file, narrationPluginParams(index))
                endPluginOpen()
                withContext(Dispatchers.IO) { n.onEditVerse(index, file).blockingAwait() }
                refreshVerses()
                resetNarratableList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("editing a verse with an external plugin", e)
                endPluginOpen()
                System.err.println("Narration external edit failed: $e")
            }
        }
    }

    private fun narrationPluginParams(index: Int): org.bibletranslationtools.otter.common.domain.plugins.PluginParameters {
        val wb = workbookDataStore.activeWorkbook.value
        val chapter = workbookDataStore.activeChapter.value
        val verseLabel = narration?.totalVerses?.getOrNull(index)?.label
        return org.bibletranslationtools.otter.common.domain.plugins.PluginParameters(
            languageName = wb?.target?.language?.name ?: "",
            bookSlug = wb?.target?.slug ?: "",
            bookTitle = wb?.target?.title ?: (wb?.target?.slug ?: ""),
            chapterLabel = chapter?.title ?: chapter?.sort?.toString() ?: "",
            chapterNumber = chapter?.sort ?: 1,
            verseTotal = narration?.totalVerses?.size,
            verseLabels = verseLabel?.let { listOf(it) },
            sourceLanguageName = wb?.source?.language?.name
        )
    }

    /** Shared teleprompter Pause button — dispatches by current state (normal vs re-record). */
    fun onPauseRecording(index: Int) {
        val n = narration ?: return
        n.pauseRecording()
        n.finalizeVerse(index)
        // Drop the stale live-record buffer so it isn't composited into subsequent renders
        // (JVM clears it here too, except mid-prepend).
        if (!isPrependRecording) _audioScene.value?.clear()
        val transition = if (stateMachine?.getNarrationContext() == NarrationStateType.RECORDING_AGAIN) {
            NarrationStateTransition.PAUSE_RECORD_AGAIN
        } else {
            NarrationStateTransition.PAUSE_RECORDING
        }
        performTransition(transition, index)
    }

    /** Shared teleprompter Resume button — dispatches by current state (normal vs re-record). */
    fun onResumeRecording(index: Int) {
        val n = narration ?: return
        stopPlayer()
        if (stateMachine?.getNarrationContext() == NarrationStateType.RECORDING_AGAIN_PAUSED) {
            n.resumeRecordingAgain()
            performTransition(NarrationStateTransition.RESUME_RECORD_AGAIN, index)
        } else {
            val verse = if (recordingVerseIndex >= 0) recordingVerseIndex else index
            recordingVerseIndex = verse
            n.resumeRecording(verse)
            performTransition(NarrationStateTransition.RESUME_RECORDING, verse)
        }
    }

    // ---- playback -----------------------------------------------------------------------

    fun onPlayVerse(index: Int) {
        val n = narration ?: return
        _audioScene.value?.clear() // playback shows only recorded audio (no stale live take)
        val player = n.getPlayer()

        // The verse play/pause button toggles onPausePlayback <-> onPlayVerse. Distinguish RESUMING the
        // same verse that was paused mid-play from a FRESH start (a different verse, the first play, or
        // one that reached its end -> playbackReachedEnd).
        //  - RESUME: just play() again and let the clock continue from where it froze at pause. Do NOT
        //    reload the section (that re-locks + seeks to the section start) and do NOT re-snap the
        //    clock -- the audio resumes from the player's own paused position and the frozen clock IS
        //    the pause point. Reloading here was the "jumps instead of resuming" bug.
        //  - FRESH: reload the section and snap to the verse's KNOWN chapter-space start
        //    (activeVerses[..].location). We must NOT read getLocationInFrames() here: pause()'s async
        //    `lastPosition = workerPosition` can land after loadSectionIntoPlayer's synchronous
        //    seek(0), clobbering the position back to the previous playhead (the stale 29100/55775/...
        //    values in the logs).
        val resuming = playingVerseIndex == index && !playbackReachedEnd
        playbackReachedEnd = false
        playingVerseIndex = index

        val start: Int
        if (resuming) {
            start = clock.displayFrame.toInt()
            // Seek to the verse-RELATIVE resume position before play(). The section reader is locked
            // to this verse, so its seek/position/totalFrames are verse-relative; without this seek,
            // AudioPlayerConnection.play() sees the paused worker position >= the verse length and
            // auto-rewinds to 0 ("jumps back to the beginning" when paused near the verse end).
            val relStart = (start - verseStartFrame(index)).coerceAtLeast(0)
            System.err.println("[narr-diag] PLAY VERSE index=$index RESUME start=$start rel=$relStart label=${n.totalVerses.getOrNull(index)?.label}")
            n.seek(relStart)
            player.play()
        } else {
            player.pause()
            n.loadSectionIntoPlayer(n.totalVerses[index])
            start = verseStartFrame(index)
            System.err.println("[narr-diag] PLAY VERSE index=$index FRESH start=$start label=${n.totalVerses.getOrNull(index)?.label}")
            player.play()
            clock.snapTo(start.toLong())
        }
        clock.advancing = true
        performTransition(NarrationStateTransition.PLAY_AUDIO, index)
        startPositionTicker()
        launchLogged {
            kotlinx.coroutines.delay(150)
            System.err.println("[narr-diag] PLAY VERSE +150ms index=$index resuming=$resuming loc=${n.getLocationInFrames()} clock=${clock.displayFrame}")
        }
    }

    /** The chapter-space start frame of a verse, taken from its recorded marker (deterministic — no
     *  racy getLocationInFrames() read after an async section load). activeVerses locations are in the
     *  same chapter space as the display clock. */
    private fun verseStartFrame(index: Int): Int {
        val n = narration ?: return 0
        val label = n.totalVerses.getOrNull(index)?.formattedLabel ?: return 0
        return n.activeVerses.firstOrNull { it.formattedLabel == label }?.location ?: 0
    }

    /** The chapter-space END frame of a verse: the next active verse's start after this one, else the
     *  chapter end. Verses are contiguous, so verse[i].end == verse[i+1].start. Used to rest the
     *  playhead exactly on the verse boundary when a single verse finishes (the player under-reports
     *  its completion position by ~one audio buffer). */
    private fun verseEndFrame(index: Int): Int {
        val n = narration ?: return 0
        val start = verseStartFrame(index)
        return n.activeVerses.map { it.location }.filter { it > start }.minOrNull() ?: n.getDurationInFrames()
    }

    fun onPlayAll() {
        val n = narration ?: return
        _audioScene.value?.clear()
        playingVerseIndex = -1
        val player = n.getPlayer()
        player.pause()
        n.loadChapterIntoPlayer() // unlock + clear verse bounds
        // The display clock is the trustworthy playback position — it tracks real playback at the
        // sample rate on the wall clock and, unlike n.getLocationInFrames(), does NOT double after a
        // resume (the player re-anchors sessionStartFrame to an already-inflated position). So resume
        // from the clock (or 0 if the last playback ran to the end), and SEEK the player there
        // explicitly so both the audio and the player's own sessionStart are re-anchored accurately —
        // this is what breaks the per-cycle "jump ahead" compounding.
        val resume = if (playbackReachedEnd) 0 else clock.displayFrame.toInt()
        System.err.println("[narr-diag] PLAY resume=$resume clockDisplay=${clock.displayFrame} reachedEnd=$playbackReachedEnd loc=${n.getLocationInFrames()} total=${n.getTotalFrames()} dur=${n.getDurationInFrames()} windowFrames=441000 (~${n.getTotalFrames() / 44100.0}s vs 10s window)")
        playbackReachedEnd = false
        n.seek(resume)
        player.play()
        clock.snapTo(resume.toLong())
        clock.advancing = true
        // The state machine rejects PLAY_AUDIO (all) while any verse is mid/paused-recording;
        // skip the transition in that case (the audio still plays) instead of logging an error.
        if (canTransitionToPlay()) performTransition(NarrationStateTransition.PLAY_AUDIO)
        startPositionTicker()
    }

    private fun canTransitionToPlay(): Boolean {
        val ctx = stateMachine?.getNarrationContext()
        return ctx != NarrationStateType.RECORDING && ctx != NarrationStateType.RECORDING_AGAIN &&
            ctx != NarrationStateType.RECORDING_PAUSED && ctx != NarrationStateType.RECORDING_AGAIN_PAUSED
    }

    fun onTogglePlayAll() {
        if (_uiState.value.isPlaying) onPausePlayback() else onPlayAll()
    }

    fun onPausePlayback() {
        val n = narration ?: return
        System.err.println("[narr-diag] PAUSE clock=${clock.displayFrame} loc=${n.getLocationInFrames()}")
        performTransition(NarrationStateTransition.PAUSE_AUDIO_PLAYBACK, playingVerseIndex.takeIf { it >= 0 })
        n.getPlayer().pause()
        clock.advancing = false
        stopPositionTicker()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    fun onSeekToFraction(fraction: Float) {
        val n = narration ?: return
        val frame = (fraction.coerceIn(0f, 1f) * n.getDurationInFrames()).toInt()
        n.seek(frame, true)
        clock.snapTo(frame.toLong())
        playbackReachedEnd = false
        syncHighlightNow()
    }

    /** Toolbar prev/next: jump the playhead to the previous / next verse marker (JVM seekTo*). */
    fun onSeekPreviousMarker() {
        narration?.seekToPrevious()
        narration?.let { clock.snapTo(it.getLocationInFrames().toLong()) }
        playbackReachedEnd = false
        syncHighlightNow()
    }

    fun onSeekNextMarker() {
        narration?.seekToNext()
        narration?.let { clock.snapTo(it.getLocationInFrames().toLong()) }
        playbackReachedEnd = false
        syncHighlightNow()
    }

    /**
     * Seek the playhead to an absolute chapter frame (JVM: seekTo) — used by the scrollbar and
     * the scrub-drag. Clamped to [0, total]; the waveform ticker redraws at the new position.
     */
    fun seekToFrame(frame: Int) {
        val n = narration ?: return
        val clamped = frame.coerceIn(0, n.getTotalFrames())
        n.seek(clamped, true)
        audioPositionFrames = clamped
        clock.snapTo(clamped.toLong())
        playbackReachedEnd = false
        syncHighlightNow()
    }

    /** Update the highlighted verse from the current position (the waveform ticker moves the view). */
    private fun syncHighlightNow() {
        val n = narration ?: return
        _uiState.value = _uiState.value.copy(highlightedVerseIndex = highlightIndexAt(n.getLocationInFrames()))
    }

    private fun stopPlayer() {
        narration?.getPlayer()?.pause()
        clock.advancing = false
        stopPositionTicker()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    private fun startPositionTicker() {
        stopPositionTicker()
        _uiState.value = _uiState.value.copy(isPlaying = true)
        positionTickerJob = launchLogged {
            var prevClock = clock.displayFrame
            var tick = 0
            while (isActive) {
                val n = narration ?: break
                // The waveform ticker scrolls the view; here we only track the highlighted verse.
                _uiState.value = _uiState.value.copy(highlightedVerseIndex = highlightIndexAt(n.getLocationInFrames()))
                // Catch a sudden backward jump of the clock (the "jumps to the beginning" symptom):
                // log whenever the display clock drops by more than ~0.5s between ticks, plus a
                // periodic heartbeat.
                val now = clock.displayFrame
                if (now < prevClock - 22050) {
                    System.err.println("[narr-diag] CLOCK JUMP BACK ${prevClock} -> ${now} (loc=${n.getLocationInFrames()})")
                }
                if (tick++ % 20 == 0) {
                    System.err.println("[narr-diag] TICK clock=$now loc=${n.getLocationInFrames()}")
                }
                prevClock = now
                delay(50)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    /** The verse index highlighted at playback position [pos] (JVM: updateHighlightedItem). */
    private fun highlightIndexAt(pos: Int): Int {
        val n = narration ?: return -1
        if (playingVerseIndex >= 0) return playingVerseIndex
        val current = n.activeVerses.sortedBy { it.location }.lastOrNull { it.location <= pos } ?: return -1
        // Match by formattedLabel: activeVerses carry relative locations (so `indexOf` fails)
        // and plain labels can collide (chapter "1" vs verse "1").
        return n.totalVerses.indexOfFirst { it.formattedLabel == current.formattedLabel }
    }

    override fun onCleared() {
        if (_uiState.value.isPluginOpen) navigationLock.unlock()
        stopPositionTicker()
        waveformTickerJob?.cancel()
        peakBuildJob?.cancel()
        clock.advancing = false
        volumeJob?.cancel()
        playerEventsJob?.cancel()
        _audioScene.value?.close()
        runCatching { sceneReader?.release() }
        activeVersesDisposable?.dispose()
        narration?.close()
        super.onCleared()
    }

    private class Prepared(
        val narration: Narration,
        val sourceText: LoadChapterSourceText.ChapterSourceText
    )
}

/**
 * A [PcmSource] over the WHOLE active-verse chapter, so the shared peak-cache engine can render
 * narration playback frame-stably (like the translation surfaces). [openReader] returns a fresh
 * composite [Narration.audioReader] connection — sequentially readable from frame 0 to
 * [Narration.getDurationInFrames], in the same relative-chapter frame space as the playhead
 * ([Narration.getLocationInFrames]) and the verse markers. [revision] tags each rebuild so a stale
 * in-flight draw's cache lookup misses cleanly until the new cache is built.
 */
private class NarrationChapterPcmSource(
    private val narration: Narration,
    revision: Int
) : PcmSource {
    override val id: String = "narration-chapter-$revision"
    override val totalFrames: Int get() = narration.getDurationInFrames()
    override val sampleRate: Int = DEFAULT_SAMPLE_RATE
    override fun openReader(): AudioFileReader = narration.audioReader
}
