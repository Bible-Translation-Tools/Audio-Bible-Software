package org.bibletranslationtools.orature.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.bibletranslationtools.shared.ui.playback.AudioTimeline
import org.bibletranslationtools.shared.ui.playback.FilePcmSource
import org.bibletranslationtools.shared.ui.playback.PcmSource
import org.bibletranslationtools.shared.ui.playback.WaveformPeakCache
import org.bibletranslationtools.shared.ui.playback.buildPeakCache
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.audio.AudioMarker
import org.bibletranslationtools.otter.common.data.audio.BookMarker
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.shared.ui.playback.PlaybackDisplayClock
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.ChapterTranslationBuilder
import org.bibletranslationtools.otter.common.domain.content.TakeCreator
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.domain.model.MarkerItem
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementModel
import org.bibletranslationtools.otter.common.domain.model.MarkerPlacementType
import org.bibletranslationtools.otter.common.domain.plugins.PluginParameters
import org.bibletranslationtools.otter.common.domain.translation.AddMarkerAction
import org.bibletranslationtools.otter.common.domain.translation.DeleteMarkerAction
import org.bibletranslationtools.otter.common.domain.translation.MoveMarkerAction
import org.bibletranslationtools.otter.common.domain.translation.TakeEditAction
import org.bibletranslationtools.otter.common.domain.model.UndoableActionHistory
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.bibletranslationtools.orature.plugins.OraturePluginStore
import org.bibletranslationtools.orature.plugins.OratureExternalPlugin
import org.bibletranslationtools.orature.plugins.canLaunchPlugins
import org.bibletranslationtools.orature.plugins.launchPlugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


/** UI state for the Final Review step (JVM: `ChapterReviewViewModel`). */
data class OratureChapterReviewUiState(
    val isLoading: Boolean = true,
    val hasChapter: Boolean = false,
    val chapterTitle: String = "",
    /** Book + chapter, e.g. "Titus 1" (JVM: `PluginOpenedPage.sourceContentTitleProperty` ←
     *  `workbookDataStore.activeTitleBinding()`) — the plugin-opened cover's heading; distinct
     *  from [chapterTitle], which is just the chapter number. */
    val activeContentTitle: String = "",
    /** Mirrors `OratureTranslationUiState.sourceText`/`sourceLicense` (JVM: `SourceContent`'s own
     *  `sourceTextProperty`/`licenseProperty`) — needed here because the plugin-opened cover shows
     *  the full source text + license itself, matching JVM's `PluginOpenedPage`. */
    val sourceText: String = "",
    val sourceLicense: String = "",
    val isSourcePlaying: Boolean = false,
    val isPlaying: Boolean = false,
    /** The source audio's playback rate (JVM: `SimpleAudioPlayer.audioPlaybackRateProperty`). */
    val sourceRate: Double = 1.0,
    val sourceDurationMs: Int = 0,
    /** Source playhead position (ms) — kept live whether playing or paused. */
    val sourcePositionMs: Int = 0,
    /** Placed verse markers on the compiled chapter take. */
    val markers: List<OratureMarkerInfo> = emptyList(),
    /** True when every required (source) marker is placed and there is a next chapter to go to. */
    val canGoNextChapter: Boolean = false,
    /** Whether the "Add Marker" split-button's Book/Chapter options are enabled (JVM:
     *  `canAddBookMarkerProperty`/`canAddChapterMarkerProperty`) — only offered when that marker
     *  type is required for this chapter and isn't already placed. */
    val canAddBookMarker: Boolean = false,
    val canAddChapterMarker: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** True while an external editor plugin has the (duplicated) chapter take open (JVM:
     *  `pluginOpenedProperty` — shows `PluginOpenedPage` in place of the normal review body). */
    val isPluginOpen: Boolean = false,
    val error: String? = null
)

/**
 * Drives the Final Review step (JVM: `ChapterReviewViewModel`): compiles the chapter take from the
 * chunk takes, shows it as a waveform with editable verse markers (add at playhead / move / delete,
 * all undoable), plays source (top) and the compiled take (center), and advances to the next chapter
 * once all required markers are placed. Markers are written back to the take on leaving the step.
 *
 * The "Add Marker" control is a split button (JVM: `AddMarkerSplitButton`): the primary action
 * places the next verse marker; a menu on the side offers Book Marker (chapter 1 only, once) and
 * Chapter Marker (every chapter, once), each placeable independent of verse-marker sequence.
 */
class OratureChapterReviewViewModel(
    private val translationVm: OratureTranslationViewModel
) : ViewModel(), KoinComponent {

    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()
    private val chapterTranslationBuilder: ChapterTranslationBuilder by inject()
    private val takeCreator: TakeCreator by inject()
    private val pluginStore: OraturePluginStore by inject()
    private val navigationLock: org.bibletranslationtools.orature.ui.OratureNavigationLock by inject()

    private val _uiState = MutableStateFlow(OratureChapterReviewUiState())
    val uiState: StateFlow<OratureChapterReviewUiState> = _uiState.asStateFlow()

    private var workbook: Workbook? = null
    private var chapter: Chapter? = null
    private var markerModel: MarkerPlacementModel? = null
    private var sourcePlayer: IAudioPlayer? = null
    private var takePlayer: IAudioPlayer? = null
    // Shared waveform engine: the take rendered as a single-segment timeline whose peaks are built
    // once (off-thread, progressively) into an in-memory cache the draw samples per pixel — no
    // per-tick decode/allocation (JVM/recorder: WaveformPeakCache + AudioTimeline.fillWindow).
    private var timeline: AudioTimeline? = null
    private var peakCache: WaveformPeakCache? = null
    private var peakSource: PcmSource? = null
    private var peakBuildJob: Job? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var markerInfos: List<OratureMarkerInfo> = emptyList()
    private var waveformTickerJob: Job? = null
    private var sourceTickerJob: Job? = null

    // Rate-locked display clock (shared with the recorder). The screen advances it every display
    // frame; the waveform draws clock.displayFrame for smooth, sub-pixel scrolling instead of the
    // 30 fps steps of the ticker below. positionSource/reliable read the take player live.
    val clock = PlaybackDisplayClock(
        positionSource = { takePlayer?.getLocationInFrames()?.toLong() ?: 0L },
        positionReliable = { takePlayer?.isPositionReliable() ?: false }
    )
    private var clockEventsJob: Job? = null

    private val actionHistory = UndoableActionHistory<IUndoable>()

    fun currentTimeline(): AudioTimeline? = timeline
    fun peakCacheFor(source: PcmSource): WaveformPeakCache? =
        if (source.id == peakSource?.id) peakCache else null
    fun waveformSampleRate(): Int = sampleRate
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentMarkers(): List<OratureMarkerInfo> = markerInfos

    init {
        translationVm.setUndoRedoHandlers(::undo, ::redo)
        translationVm.setOpenInHandler(::processWithPlugin)
        launchLogged {
            workbookDataStore.activeChapter.collect { chap -> onChapter(chap) }
        }
        // Mirror the shell's source text/license (JVM: `PluginOpenedPage.sourceTextProperty`/
        // `licenseProperty`, bound from `WorkbookDataStore`) so the plugin-opened cover can show
        // the chapter's full source text without re-deriving it here.
        launchLogged {
            translationVm.uiState.collect { t ->
                if (_uiState.value.sourceText != t.sourceText || _uiState.value.sourceLicense != t.sourceLicense) {
                    _uiState.value = _uiState.value.copy(sourceText = t.sourceText, sourceLicense = t.sourceLicense)
                }
            }
        }
    }

    private fun onChapter(chap: Chapter?) {
        // Persist markers of the chapter we're leaving before loading another.
        writeMarkersBlocking()
        stopAll()
        actionHistory.clear()
        translationVm.updateChunkUndoRedo(canUndo = false, canRedo = false)
        chapter = chap
        if (chap == null) {
            _uiState.value = OratureChapterReviewUiState(hasChapter = false, isLoading = false)
            return
        }
        // Final Review is chapter-level; clear the active chunk so the source-text drawer shows the
        // whole chapter's text (JVM: dock sets activeChunkProperty = null).
        workbookDataStore.setActiveChunk(null)
        _uiState.value = OratureChapterReviewUiState(isLoading = true, hasChapter = true, chapterTitle = chap.title)
        loadChapterTake(chap)
    }

    private fun loadChapterTake(chap: Chapter) {
        launchLogged {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val wb = workbookDataStore.activeWorkbook.value ?: error("No active workbook")
                    workbook = wb
                    // Compile (or reuse) the chapter take from the chunk takes (JVM: getOrCompile).
                    val take = chapterTranslationBuilder.getOrCompile(wb, chap).await()
                    prepareFromTake(wb, chap, take)
                }
                applyPrepared(prepared, chap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading the chapter take for review", e)
                _uiState.value = OratureChapterReviewUiState(
                    hasChapter = true, isLoading = false, error = e.message ?: "Unknown error"
                )
            }
        }
    }

    /** Build the marker model / waveform peaks / source-player prep from a GIVEN take file, with no
     *  compile step — used both by the normal load path (after `getOrCompile` resolves a take) and
     *  by the post-plugin-edit reload path (which already knows exactly which take is now selected
     *  and must NOT re-compile from chunks, or a plugin's edits to the compiled audio would be
     *  overwritten by a fresh chunk-concatenation on the next reload). */
    private fun prepareFromTake(wb: Workbook, chap: Chapter, take: Take): Prepared {
        val takeAudio = OratureAudioFile(take.file)

        // Reserved marker set = source verse/title markers; placed markers = those already
        // on the compiled take (JVM: loadVerseMarkers).
        val sourceMarkers = sourceMarkers(wb, chap)
        val model = MarkerPlacementModel(
            MarkerPlacementType.VERSE,
            takeAudio,
            sourceMarkers.map { it.clone(0) }
        ).apply {
            loadMarkers(takeAudio.getVerseAndTitleMarkers().map { MarkerItem(it, true) })
        }

        // Single-segment timeline + empty peak cache for the shared renderer; the cache is filled
        // progressively off-thread in applyPrepared (the draw shows peaks as they build).
        val source = FilePcmSource(take.file)
        val sr = source.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val totalFrames = source.totalFrames
        val tl = AudioTimeline.ofWholeSource(source)
        val cache = WaveformPeakCache(totalFrames)
        val sourcePrep = if (sourcePlayer == null) prepareSourcePlayer(wb, chap) else null
        return Prepared(model, take.file, totalFrames, sr, source, tl, cache, sourcePrep?.first, sourcePrep?.second ?: 0)
    }

    /** Apply a [Prepared] result to VM state, (re)connect the take player, and (re)start the
     *  waveform ticker. If a source player was already running (kept alive across a plugin-edit
     *  reload), it's left untouched instead of being replaced. */
    private fun applyPrepared(prepared: Prepared, chap: Chapter) {
        markerModel = prepared.model
        sampleRate = prepared.sampleRate
        totalFrames = prepared.totalFrames
        positionFrames = 0
        timeline = prepared.timeline
        peakCache = prepared.cache
        peakSource = prepared.source
        // Fill the peak cache off-thread; the draw reads builtBuckets (snapshot) and shows the wave
        // as it fills (JVM/recorder: buildPeakCache streamed on Dispatchers.IO).
        peakBuildJob?.cancel()
        peakBuildJob = launchLogged(Dispatchers.IO) {
            runCatching { buildPeakCache(prepared.source, prepared.cache) }
        }
        if (prepared.sourcePlayer != null) sourcePlayer = prepared.sourcePlayer

        runCatching { takePlayer?.pause(); takePlayer?.release() }
        val p = AudioPlayerConnection(TAKE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
        p.load(OratureAudioFile(prepared.takeFile).reader().apply { open() })
        takePlayer = p
        // Point the display clock at the new take and follow its transport events (main thread).
        clock.sampleRate = sampleRate
        clock.durationFrames = totalFrames.toLong()
        clock.advancing = false
        clock.snapTo(0L)
        observePlayerForClock(p)

        refreshMarkers()
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            chapterTitle = chap.title,
            activeContentTitle = "${workbook?.target?.title.orEmpty()} ${chap.title}".trim(),
            sourceDurationMs = if (prepared.sourcePlayer != null) prepared.sourceDurationMs else _uiState.value.sourceDurationMs
        )
        startWaveformTicker()
        startSourceTicker()
    }

    /** Drive the display clock from the take player's transport events (main thread, per the clock's
     *  threading contract) — mirrors the recorder's PlaybackViewModel. Re-created for each take. */
    private fun observePlayerForClock(p: IAudioPlayer) {
        clockEventsJob?.cancel()
        clockEventsJob = launchLogged {
            p.events.collect { e ->
                when (e) {
                    AudioPlayerEvent.Play -> clock.advancing = true
                    AudioPlayerEvent.Pause -> clock.advancing = false
                    AudioPlayerEvent.Stop -> { clock.advancing = false; clock.snapTo(clock.displayFrame) }
                    AudioPlayerEvent.Complete -> { clock.advancing = false; clock.snapTo(clock.durationFrames) }
                    is AudioPlayerEvent.Error -> clock.advancing = false
                    else -> Unit
                }
            }
        }
    }

    private data class Prepared(
        val model: MarkerPlacementModel,
        val takeFile: java.io.File,
        val totalFrames: Int,
        val sampleRate: Int,
        val source: PcmSource,
        val timeline: AudioTimeline,
        val cache: WaveformPeakCache,
        val sourcePlayer: IAudioPlayer?,
        val sourceDurationMs: Int
    )

    /**
     * Source verse/title markers used as the required marker set (JVM: `getSourceMarkers` +
     * `loadVerseMarkers`'s `optionalMarkers`). Book/Chapter markers are TARGET-recorded, not
     * inherited from source — a Book marker is required for chapter 1 (and only chapter 1) if the
     * source doesn't already carry one, and a Chapter marker is always required if the source
     * doesn't carry one, so both are added here as unplaced (`location = -1`) placeholders even
     * when absent from source, matching JVM's `optionalMarkers` construction exactly.
     */
    private fun sourceMarkers(wb: Workbook, chap: Chapter): List<AudioMarker> {
        val sa = runCatching { wb.sourceAudioAccessor.getChapter(chap.sort, wb.target) }.getOrNull()
        val fromSource = if (sa != null) {
            runCatching { OratureAudioFile(sa.file).getVerseAndTitleMarkers() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val optional = buildList {
            if (fromSource.none { it is BookMarker } && chap.sort == 1) {
                add(BookMarker(wb.target.slug, -1))
            }
            if (fromSource.none { it is ChapterMarker }) {
                add(ChapterMarker(chap.sort, -1))
            }
        }
        return fromSource + optional
    }

    private fun prepareSourcePlayer(wb: Workbook, chap: Chapter): Pair<IAudioPlayer, Int>? {
        val sa = runCatching {
            wb.sourceAudioAccessor.getUserMarkedChapter(chap.sort, wb.target)
                ?: wb.sourceAudioAccessor.getChapter(chap.sort, wb.target)
        }.getOrNull() ?: return null
        val reader = OratureAudioFile(sa.file).reader().apply { open() }
        val sr = reader.spec.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val durationMs = (reader.totalFrames.toLong() * 1000 / sr).toInt()
        val player = AudioPlayerConnection(SOURCE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
            .also { it.load(reader) }
        return player to durationMs
    }

    /** The configured default editor plugin, if external editing is available (desktop + one
     *  selected) — same lookup as `OratureBlindDraftViewModel.selectedEditor`. */
    private fun selectedEditor(): OratureExternalPlugin? {
        if (!canLaunchPlugins()) return null
        val reg = pluginStore.load()
        return reg.plugins.firstOrNull { it.id == reg.selectedEditorId && it.canEdit }
    }

    /** Translation context handed to the plugin (JVM: `PluginParameters`) — chapter-scoped, no
     *  chunk fields, since Final Review edits the whole compiled chapter take. */
    private fun pluginParams(wb: Workbook, chap: Chapter): PluginParameters {
        val sourceAudio = runCatching { wb.sourceAudioAccessor.getChapter(chap.sort, wb.target)?.file }.getOrNull()
        return PluginParameters(
            languageName = wb.target.language.name,
            bookSlug = wb.target.slug,
            bookTitle = wb.target.title.ifEmpty { wb.target.slug },
            chapterLabel = chap.title,
            chapterNumber = chap.sort,
            verseTotal = null,
            sourceChapterAudio = sourceAudio,
            sourceLanguageName = wb.source.language.name
        )
    }

    /**
     * Open the compiled chapter take in the configured external editor (JVM: `OpenInPluginEvent` →
     * `processWithPlugin`). Markers are saved first, then the CURRENT take is duplicated into a new
     * one (JVM: `createDuplicateTake`) so a cancelled/failed edit leaves the original untouched; the
     * new take is what's handed to the plugin. While the plugin runs, `isPluginOpen` shows the
     * plugin-opened cover (JVM: `PluginOpenedPage`) in place of the normal review body — the take
     * player is released (its file is being edited externally) but the SOURCE player is deliberately
     * left running so the cover can still offer source playback, matching JVM's page. On success the
     * edit is wrapped as an undoable [TakeEditAction] (undo/redo restore the corresponding take and
     * reload from it); on failure/no-plugin the original take is re-selected. No-ops if no editor is
     * configured (same silent-no-op precedent as `OratureBlindDraftViewModel.editTakeExternally`).
     */
    fun processWithPlugin() {
        if (_uiState.value.isPluginOpen) return // already open; ignore a re-click
        val wb = workbook ?: return
        val chap = chapter ?: return
        val editor = selectedEditor() ?: return
        val existingTake = chap.audio.getSelectedTake() ?: return

        launchLogged {
            writeMarkersBlocking()
            waveformTickerJob?.cancel()
            runCatching { takePlayer?.pause(); takePlayer?.release() }
            takePlayer = null
            _uiState.value = _uiState.value.copy(isPluginOpen = true)
            // Lock in-app navigation (step/chapter switching, back, AND the app rail's Home/
            // Settings/Info — the rail sits outside the translation page entirely, hence the
            // separate app-scoped lock) while the plugin has a take open — leaving mid-edit would
            // clear this VM (cancelling viewModelScope) with the external process still running
            // and the edit never wrapped in an undo action.
            translationVm.setPluginOpen(true)
            navigationLock.lock()

            val newTake = withContext(Dispatchers.IO) {
                val namer = WorkbookFileNamerBuilder.createFileNamer(
                    workbook = wb, chapter = chap, chunk = null, recordable = chap, rcSlug = wb.sourceMetadataSlug
                )
                val chapterAudioDir = wb.projectFilesAccessor.audioDir
                    .resolve(namer.formatChapterNumber())
                    .apply { mkdirs() }
                val takeNumber = chap.audio.getNewTakeNumberSuspend()
                takeCreator.createNewTake(
                    takeNumber,
                    namer.generateName(takeNumber, AudioFileFormat.WAV),
                    chapterAudioDir,
                    createEmpty = false
                ).also { existingTake.file.copyTo(it.file, overwrite = true) }
            }
            chap.audio.insertTake(newTake)
            chap.audio.selectTake(newTake)

            val success = withContext(Dispatchers.IO) {
                runCatching { launchPlugin(editor, newTake.file, pluginParams(wb, chap)) }.getOrDefault(false)
            }

            if (success) {
                val action = TakeEditAction(chap.audio, newTake, existingTake).apply {
                    setUndoCallback { reloadFromSelectedTake(chap) }
                    setRedoCallback { reloadFromSelectedTake(chap) }
                }
                actionHistory.execute(action)
                onUndoableAction()
            } else {
                chap.audio.selectTake(existingTake)
            }

            _uiState.value = _uiState.value.copy(isPluginOpen = false)
            translationVm.setPluginOpen(false)
            navigationLock.unlock()
            reloadFromSelectedTake(chap)
        }
    }

    /** Rebuild the marker model / waveform / take player from whichever take is CURRENTLY selected
     *  (post plugin-edit, or its undo/redo) — deliberately bypasses `getOrCompile` (see
     *  [prepareFromTake]'s doc). */
    private fun reloadFromSelectedTake(chap: Chapter) {
        val wb = workbook ?: return
        val take = chap.audio.getSelectedTake() ?: return
        launchLogged {
            try {
                val prepared = withContext(Dispatchers.IO) { prepareFromTake(wb, chap, take) }
                applyPrepared(prepared, chap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("reloading chapter review from the selected take", e)
                _uiState.value = _uiState.value.copy(error = e.message ?: "Unknown error")
            }
        }
    }

    /** Change the source-audio playback rate (JVM: playback-speed menu — `WaMenuButton`). */
    fun setSourceRate(rate: Double) {
        sourcePlayer?.changeRate(rate)
        _uiState.value = _uiState.value.copy(sourceRate = rate)
    }

    /** Scrub the source audio to [fraction] (0f..1f) of its duration. */
    fun seekSource(fraction: Float) {
        val p = sourcePlayer ?: return
        val frame = (p.getDurationInFrames() * fraction.coerceIn(0f, 1f)).toInt()
        p.seek(frame)
    }

    /**
     * Play/pause the source audio. State isn't written optimistically here — `startWaveformTicker`
     * re-derives `isSourcePlaying`/`isPlaying`/`sourcePositionMs` from the real player each tick
     * (matches `OratureBlindDraftViewModel.toggleSource`, avoiding a race with the async player
     * connection and keeping a paused slider from snapping back).
     */
    fun toggleSource() {
        val p = sourcePlayer ?: return
        takePlayer?.pause()
        if (p.isPlaying()) p.pause() else p.play()
    }

    fun togglePlay() {
        val p = takePlayer ?: return
        sourcePlayer?.pause()
        if (p.isPlaying()) p.pause() else p.play()
    }

    fun pause() {
        takePlayer?.pause()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    fun seekToFrame(frame: Int) {
        val clamped = frame.coerceIn(0, totalFrames)
        takePlayer?.seek(clamped)
        positionFrames = clamped
        clock.snapTo(clamped.toLong())
    }

    fun seekNext() {
        markerModel?.let { seekToFrame(it.seekNext(positionFrames)) }
    }

    fun seekPrevious() {
        markerModel?.let { seekToFrame(it.seekPrevious(positionFrames)) }
    }

    /** Add a verse marker at the playhead (JVM: placeMarker → AddMarkerAction). */
    fun placeMarker() {
        val model = markerModel ?: return
        actionHistory.execute(AddMarkerAction(model, positionFrames))
        onUndoableAction()
        refreshMarkers()
    }

    /** Add a Book marker at the playhead (JVM: `AddMarkerSplitButton`'s "Add Book Marker" menu
     *  item → `AddOptionalMarkerAction(model, BOOK, location)`). No-op if not required/available. */
    fun addBookMarker() {
        val model = markerModel ?: return
        if (!model.hasUnplacedMarkerOfType(BookMarker::class)) return
        actionHistory.execute(AddMarkerAction(model, positionFrames, BookMarker::class))
        onUndoableAction()
        refreshMarkers()
    }

    /** Add a Chapter marker at the playhead ("Add Chapter Marker" menu item). */
    fun addChapterMarker() {
        val model = markerModel ?: return
        if (!model.hasUnplacedMarkerOfType(ChapterMarker::class)) return
        actionHistory.execute(AddMarkerAction(model, positionFrames, ChapterMarker::class))
        onUndoableAction()
        refreshMarkers()
    }

    fun moveMarker(id: Int, newFrame: Int) {
        val model = markerModel ?: return
        val original = model.markerItems.firstOrNull { it.id == id }?.frame ?: return
        actionHistory.execute(MoveMarkerAction(model, id, original, newFrame.coerceIn(0, totalFrames)))
        onUndoableAction()
        refreshMarkers()
    }

    fun deleteMarker(id: Int) {
        val model = markerModel ?: return
        actionHistory.execute(DeleteMarkerAction(model, id))
        onUndoableAction()
        refreshMarkers()
    }

    fun undo() {
        if (!actionHistory.canUndo()) return
        actionHistory.undo()
        translationVm.updateChunkUndoRedo(canUndo = actionHistory.canUndo(), canRedo = true)
        refreshMarkers()
    }

    fun redo() {
        if (!actionHistory.canRedo()) return
        actionHistory.redo()
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = actionHistory.canRedo())
        refreshMarkers()
    }

    private fun onUndoableAction() {
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = false)
    }

    /** Advance to the next chapter (JVM: GoToNextChapterEvent). Saves markers first. */
    fun goToNextChapter() {
        if (!_uiState.value.canGoNextChapter) return
        writeMarkersBlocking()
        translationVm.selectNextChapter()
    }

    private fun refreshMarkers() {
        val model = markerModel
        markerInfos = model?.markerItems?.filter { it.placed }?.map {
            OratureMarkerInfo(verseIndex = it.id, location = it.frame, label = it.label, movable = true)
        }?.sortedBy { it.location } ?: emptyList()
        val allPlaced = model?.markerItems?.all { it.placed } ?: false
        _uiState.value = _uiState.value.copy(
            markers = markerInfos,
            canUndo = actionHistory.canUndo(),
            canRedo = actionHistory.canRedo(),
            canGoNextChapter = allPlaced && translationVm.uiState.value.hasNextChapter,
            canAddBookMarker = model?.hasUnplacedMarkerOfType(BookMarker::class) ?: false,
            canAddChapterMarker = model?.hasUnplacedMarkerOfType(ChapterMarker::class) ?: false
        )
    }

    /** Polls the take player each tick for the playhead position + play/pause state + the highlighted
     *  verse. No longer computes the waveform itself — the shared renderer samples the peak cache in
     *  the draw pass, so this ticker only feeds `positionFrames`/`isPlaying` (read live by the draw). */
    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = launchLogged(Dispatchers.Default) {
            while (isActive) {
                val p = takePlayer
                val current = _uiState.value
                var playing = current.isPlaying
                if (p != null) {
                    runCatching {
                        playing = p.isPlaying()
                        if (playing) positionFrames = p.getLocationInFrames()
                    }.onFailure { System.err.println("[review] take state poll failed: $it") }
                }
                if (current.isPlaying != playing) {
                    _uiState.value = _uiState.value.copy(isPlaying = playing)
                }
                updateHighlightedVerse()
                delay(33)
            }
        }
    }

    /** Polls ONLY the source player, independent of [startWaveformTicker] — kept running even while
     *  [waveformTickerJob] is cancelled during an external-plugin edit (see [processWithPlugin]),
     *  since the source player is deliberately left alive so the plugin-opened cover can still play
     *  it. Without this, the cover's play/pause icon and scrubber would freeze once the take-focused
     *  ticker stops. */
    private fun startSourceTicker() {
        sourceTickerJob?.cancel()
        sourceTickerJob = launchLogged(Dispatchers.Default) {
            while (isActive) {
                val current = _uiState.value
                val srcPlaying = runCatching { sourcePlayer?.isPlaying() }.getOrDefault(false) ?: false
                val srcPos = runCatching { sourcePlayer?.getLocationMs() }.getOrNull() ?: current.sourcePositionMs
                if (current.isSourcePlaying != srcPlaying || current.sourcePositionMs != srcPos) {
                    _uiState.value = _uiState.value.copy(isSourcePlaying = srcPlaying, sourcePositionMs = srcPos)
                }
                delay(33)
            }
        }
    }

    /**
     * Push the verse label at (or immediately before) the playhead to the shell so the source-text
     * drawer can highlight it (JVM: `ChapterReviewViewModel` binds `translationViewModel.
     * currentMarkerProperty` to its own `highlightedMarkerIndexProperty`). Only VERSE-type markers
     * are considered — Book/Chapter marker labels ("1", the chapter number) can coincidentally
     * collide with a verse number and would otherwise mis-highlight verse 1.
     */
    private fun updateHighlightedVerse() {
        val model = markerModel
        val label = model?.markerItems
            ?.filter { it.placed && it.marker is VerseMarker && it.frame <= positionFrames }
            ?.maxByOrNull { it.frame }
            ?.label
        translationVm.setHighlightedVerse(label)
    }

    /** Persist the placed verse markers back to the compiled chapter take (JVM: undock writeMarkers). */
    private fun writeMarkersBlocking() {
        val model = markerModel ?: return
        runCatching { model.writeMarkers().blockingAwait() }
            .onFailure { System.err.println("Chapter-review marker save failed: $it") }
    }

    private fun stopAll() {
        waveformTickerJob?.cancel()
        sourceTickerJob?.cancel()
        peakBuildJob?.cancel()
        clockEventsJob?.cancel()
        clock.advancing = false
        runCatching { sourcePlayer?.pause() }
        runCatching { sourcePlayer?.release() }
        runCatching { takePlayer?.pause() }
        runCatching { takePlayer?.release() }
        sourcePlayer = null
        takePlayer = null
        timeline = null
        peakCache = null
        peakSource = null
    }

    public override fun onCleared() {
        translationVm.clearUndoRedoHandlers()
        translationVm.clearOpenInHandler()
        translationVm.setHighlightedVerse(null)
        // Safety net: normal navigation is blocked while a plugin is open (see processWithPlugin),
        // but don't leave the shell's navigation lock stuck on if this VM is ever cleared anyway.
        translationVm.setPluginOpen(false)
        navigationLock.unlock()
        writeMarkersBlocking()
        stopAll()
        markerModel = null
    }

    companion object {
        private const val SOURCE_PLAYER_ID = 90_030
        private const val TAKE_PLAYER_ID = 90_031
    }
}
