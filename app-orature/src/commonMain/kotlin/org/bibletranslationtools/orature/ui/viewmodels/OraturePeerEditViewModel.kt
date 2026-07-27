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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.ui.translation.ChunkingStep
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.bibletranslationtools.shared.ui.playback.AudioTimeline
import org.bibletranslationtools.shared.ui.playback.FilePcmSource
import org.bibletranslationtools.shared.ui.playback.PcmSource
import org.bibletranslationtools.shared.ui.playback.WaveformPeakCache
import org.bibletranslationtools.shared.ui.playback.buildPeakCache
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.shared.ui.playback.PlaybackDisplayClock
import org.bibletranslationtools.otter.common.domain.IUndoable
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.domain.model.UndoableActionHistory
import org.bibletranslationtools.otter.common.domain.translation.TranslationTakeApproveAction
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer
import org.bibletranslationtools.otter.common.recorder.WavFileWriter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDate


/** UI state for the Peer Edit step (JVM: `PeerEditViewModel`). */
data class OraturePeerEditUiState(
    val isLoading: Boolean = false,
    val hasChunk: Boolean = false,
    /** True when the active chunk has no selected take to review (guard; shouldn't occur once gated). */
    val noTake: Boolean = false,
    val chunkTitle: String = "",
    val isSourcePlaying: Boolean = false,
    /** Target-take playback (the center waveform). */
    val isPlaying: Boolean = false,
    /** True once this chunk has been confirmed at the peer-edit stage (JVM: chunkConfirmed). */
    val confirmed: Boolean = false,
    val recording: Boolean = false,
    val recordingActive: Boolean = false,
    /** True when the current take can be opened in a configured external editor (desktop only). */
    val canEditExternally: Boolean = false,
    /** True while an external plugin (editor or recorder) has a take open (JVM:
     *  `pluginOpenedProperty` — shows a plugin-opened cover in place of the normal body). */
    val isPluginOpen: Boolean = false,
    /** Mirrors `OratureTranslationUiState`'s book+chapter title/source text/license — needed here
     *  because the plugin-opened cover shows the chapter's source text itself. */
    val activeContentTitle: String = "",
    val sourceText: String = "",
    val sourceLicense: String = "",
    /** The source audio's playback rate + duration/position — needed by the plugin-opened cover's
     *  source player (JVM: `SimpleAudioPlayer` properties). */
    val sourceRate: Double = 1.0,
    val sourceDurationMs: Int = 0,
    val sourcePositionMs: Int = 0,
    val error: String? = null
)

/**
 * Drives the Peer Edit step for the active chunk (JVM: `PeerEditViewModel`): plays the chunk's source
 * audio (top), shows the chunk's selected target take as a playback waveform (center), and lets the
 * reviewer Confirm the take (advancing its checking status) or Record a replacement. Confirm and the
 * (re)record are undoable and drive the page header undo/redo. Follows the shared active-chunk
 * selection; reuses the Consume waveform pipeline + the Blind Draft recording pipeline.
 */
class OraturePeerEditViewModel(
    private val translationVm: OratureTranslationViewModel
) : ViewModel(), KoinComponent {

    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()
    private val recorderFactory: AudioRecorderConnectionFactory by inject()
    private val pluginStore: org.bibletranslationtools.orature.plugins.OraturePluginStore by inject()
    private val navigationLock: org.bibletranslationtools.orature.ui.OratureNavigationLock by inject()

    private val _uiState = MutableStateFlow(OraturePeerEditUiState())
    val uiState: StateFlow<OraturePeerEditUiState> = _uiState.asStateFlow()

    private var activeChunk: Chunk? = null
    private var selectedTake: Take? = null
    private var sourcePlayer: IAudioPlayer? = null
    private var takePlayer: IAudioPlayer? = null
    // Shared waveform engine for the target-take waveform (see OratureChapterReviewViewModel).
    private var timeline: AudioTimeline? = null
    private var peakCache: WaveformPeakCache? = null
    private var peakSource: PcmSource? = null
    private var peakBuildJob: Job? = null
    // Rate-locked display clock (see OratureChapterReviewViewModel) for the take-waveform scroll.
    val clock = PlaybackDisplayClock(
        positionSource = { takePlayer?.getLocationInFrames()?.toLong() ?: 0L },
        positionReliable = { takePlayer?.isPositionReliable() ?: false }
    )
    private var clockEventsJob: Job? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var waveformTickerJob: Job? = null
    private var sourceTickerJob: Job? = null

    // Recording pipeline (same shape as Blind Draft).
    private var recorder: AudioRecorderConnection? = null
    private var writer: WavFileWriter? = null
    private var activeRenderer: ActiveRecordingRenderer? = null
    private var pendingTake: Take? = null
    private val recordingActiveFlow = MutableStateFlow(false)
    private val emptyWave = FloatArray(RECORD_WIDTH * 2)

    private val actionHistory = UndoableActionHistory<IUndoable>()

    // Waveform providers read by the screen each display frame.
    fun currentTimeline(): AudioTimeline? = timeline
    fun peakCacheFor(source: PcmSource): WaveformPeakCache? =
        if (source.id == peakSource?.id) peakCache else null
    fun waveformSampleRate(): Int = sampleRate
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentRecordingWaveform(): FloatArray = activeRenderer?.floatBuffer?.array ?: emptyWave

    init {
        translationVm.setUndoRedoHandlers(::undo, ::redo)
        viewModelScope.launch {
            workbookDataStore.activeChunk.collect { chunk -> onChunk(chunk) }
        }
        // Mirror the shell's book/chapter title + source text/license (JVM: `SourceContent`'s own
        // properties) so the plugin-opened cover can show them without re-deriving them here.
        viewModelScope.launch {
            translationVm.uiState.collect { t ->
                val title = "${t.bookTitle} ${t.activeChapterTitle}".trim()
                if (_uiState.value.activeContentTitle != title ||
                    _uiState.value.sourceText != t.sourceText ||
                    _uiState.value.sourceLicense != t.sourceLicense
                ) {
                    _uiState.value = _uiState.value.copy(
                        activeContentTitle = title,
                        sourceText = t.sourceText,
                        sourceLicense = t.sourceLicense
                    )
                }
            }
        }
    }

    private fun onChunk(chunk: Chunk?) {
        stopAll()
        if (chunk?.sort != activeChunk?.sort) {
            actionHistory.clear()
            translationVm.updateChunkUndoRedo(canUndo = false, canRedo = false)
        }
        activeChunk = chunk
        if (chunk == null) {
            _uiState.value = OraturePeerEditUiState(hasChunk = false)
            return
        }
        _uiState.value = OraturePeerEditUiState(isLoading = true, hasChunk = true)
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val take = chunk.audio.getSelectedTake() ?: return@withContext null
                    val audioFile = OratureAudioFile(take.file)
                    val playerReader = audioFile.reader().apply { open() }
                    val sr = playerReader.spec.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    // Single-segment timeline + empty peak cache for the shared renderer (the target
                    // take's waveform); filled off-thread below, sampled per pixel in the draw.
                    val source = FilePcmSource(take.file)
                    val tl = AudioTimeline.ofWholeSource(source)
                    val cache = WaveformPeakCache(source.totalFrames)
                    val sourcePrep = prepareSourcePlayer(chunk)
                    Prepared(take, playerReader.totalFrames, sr, source, tl, cache, sourcePrep?.first, sourcePrep?.second ?: 0)
                } ?: run {
                    _uiState.value = OraturePeerEditUiState(hasChunk = true, noTake = true)
                    return@launch
                }

                selectedTake = prepared.take
                sampleRate = prepared.sampleRate
                totalFrames = prepared.totalFrames
                positionFrames = 0
                timeline = prepared.timeline
                peakCache = prepared.cache
                peakSource = prepared.source
                peakBuildJob?.cancel()
                peakBuildJob = viewModelScope.launch(Dispatchers.IO) {
                    runCatching { buildPeakCache(prepared.source, prepared.cache) }
                }
                sourcePlayer = prepared.sourcePlayer
                val takeAudio = OratureAudioFile(prepared.take.file)
                val p = AudioPlayerConnection(TAKE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
                p.load(takeAudio.reader().apply { open() })
                takePlayer = p
                clock.sampleRate = sampleRate
                clock.durationFrames = totalFrames.toLong()
                clock.advancing = false
                clock.snapTo(0L)
                observePlayerForClock(p)

                _uiState.value = OraturePeerEditUiState(
                    isLoading = false,
                    hasChunk = true,
                    chunkTitle = "${chunk.sort}",
                    confirmed = chunk.checkingStatus().ordinal >= checkingStatusForStep().ordinal,
                    canEditExternally = selectedPlugin(recorder = false) != null,
                    sourceDurationMs = prepared.sourceDurationMs
                )
                startWaveformTicker()
                startSourceTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = OraturePeerEditUiState(hasChunk = true, error = e.message ?: "Unknown error")
            }
        }
    }

    private data class Prepared(
        val take: Take,
        val totalFrames: Int,
        val sampleRate: Int,
        val source: PcmSource,
        val timeline: AudioTimeline,
        val cache: WaveformPeakCache,
        val sourcePlayer: IAudioPlayer?,
        val sourceDurationMs: Int
    )

    /** Load JUST this chunk's slice of the chapter's source audio for the top source player (JVM:
     *  `AudioDataStore.updateSourceAudio` → `sourceAudioAccessor.getChunk(...)`). */
    private fun prepareSourcePlayer(chunk: Chunk): Pair<IAudioPlayer, Int>? {
        val wb = workbookDataStore.activeWorkbook.value ?: return null
        val chapterSort = workbookDataStore.activeChapter.value?.sort ?: return null
        val sa = runCatching {
            wb.sourceAudioAccessor.getChunk(chapterSort, chunk.sort, chunk.start, wb.target)
        }.getOrNull() ?: return null
        val reader = OratureAudioFile(sa.file).reader(sa.start, sa.end).apply { open() }
        val sr = reader.spec.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val durationMs = (reader.totalFrames.toLong() * 1000 / sr).toInt()
        val player = AudioPlayerConnection(SOURCE_PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
            .also { it.load(reader) }
        return player to durationMs
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

    /** Play/pause the source audio. State isn't written optimistically here — `startWaveformTicker`
     *  re-derives `isSourcePlaying`/`sourcePositionMs` from the real player each tick (matches
     *  `OratureBlindDraftViewModel.toggleSource`, avoiding a race with the async player connection). */
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

    /** Drive the display clock from the take player's transport events (main thread). */
    private fun observePlayerForClock(p: IAudioPlayer) {
        clockEventsJob?.cancel()
        clockEventsJob = viewModelScope.launch {
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

    /** The checking status this step confirms to (JVM: checkingStatusFromStep). The one screen serves
     *  Peer Edit, Keyword Check, and Verse Check — only the applied status differs. */
    private fun checkingStatusForStep(): CheckingStatus = when (translationVm.uiState.value.selectedStep) {
        ChunkingStep.PEER_EDIT -> CheckingStatus.PEER_EDIT
        ChunkingStep.KEYWORD_CHECK -> CheckingStatus.KEYWORD
        ChunkingStep.VERSE_CHECK -> CheckingStatus.VERSE
        else -> CheckingStatus.UNCHECKED
    }

    /** Confirm the take at this checking stage (JVM: confirmChunk → TranslationTakeApproveAction). */
    fun confirmChunk() {
        val chunk = activeChunk ?: return
        val take = chunk.audio.getSelectedTake() ?: return
        val current = take.checkingState.value ?: TakeCheckingState(CheckingStatus.UNCHECKED, null)
        val op = TranslationTakeApproveAction(take, checkingStatusForStep(), current).apply {
            setUndoCallback { _uiState.value = _uiState.value.copy(confirmed = false) }
            setRedoCallback { _uiState.value = _uiState.value.copy(confirmed = true) }
        }
        actionHistory.execute(op)
        _uiState.value = _uiState.value.copy(confirmed = true)
        onUndoableAction()
        translationVm.onChunkTakesChanged()
    }

    /** Start a re-recording (JVM: onRecordNew) — same pipeline as Blind Draft. */
    private fun selectedPlugin(recorder: Boolean): org.bibletranslationtools.orature.plugins.OratureExternalPlugin? {
        if (!org.bibletranslationtools.orature.plugins.canLaunchPlugins()) return null
        val reg = pluginStore.load()
        val id = if (recorder) reg.selectedRecorderId else reg.selectedEditorId
        return reg.plugins.firstOrNull { it.id == id && (if (recorder) it.canRecord else it.canEdit) }
    }

    private suspend fun newTake(chunk: Chunk): Take {
        val wb = workbookDataStore.activeWorkbook.value ?: error("No active workbook")
        val chapter = workbookDataStore.activeChapter.value ?: error("No active chapter")
        val takeNumber = chunk.audio.getNewTakeNumberSuspend()
        val namer = WorkbookFileNamerBuilder.createFileNamer(wb, chapter, chunk, chunk, wb.sourceMetadataSlug)
        val dir = wb.projectFilesAccessor.audioDir.resolve(namer.formatChapterNumber()).apply { mkdirs() }
        val name = namer.generateName(takeNumber, AudioFileFormat.WAV)
        return Take(name, dir.resolve(name), takeNumber, MimeType.WAV, LocalDate.now())
    }

    /** Re-record into a new take with the configured external recorder, else native (JVM:
     *  recordWithExternalPlugin). The new take auto-selects, so the chunk needs confirming again.
     *  Locks in-app navigation for the duration (see [beginPluginOpen]/[endPluginOpen]). */
    private fun recordWithExternalPlugin(chunk: Chunk) {
        if (_uiState.value.isPluginOpen) return
        val recorder = selectedPlugin(recorder = true) ?: return
        viewModelScope.launch {
            beginPluginOpen()
            val take = withContext(Dispatchers.IO) {
                val t = newTake(chunk)
                OratureAudioFile(t.file, 1, DEFAULT_SAMPLE_RATE, 16)
                t
            }
            org.bibletranslationtools.orature.plugins.launchPlugin(recorder, take.file, pluginParams(chunk))
            endPluginOpen()
            val hasAudio = runCatching { OratureAudioFile(take.file).totalFrames > 0 }.getOrDefault(false)
            if (hasAudio) chunk.audio.insertTake(take) else runCatching { take.file.delete() }
            onChunk(chunk)
            translationVm.onChunkTakesChanged()
        }
    }

    /** Open the chunk's selected take in the configured external editor, then reload (JVM: edit
     *  plugin). Locks in-app navigation for the duration (see [beginPluginOpen]/[endPluginOpen]). */
    fun editTakeExternally() {
        if (_uiState.value.isPluginOpen) return
        val chunk = activeChunk ?: return
        val take = chunk.audio.getSelectedTake() ?: return
        val editor = selectedPlugin(recorder = false) ?: return
        viewModelScope.launch {
            beginPluginOpen()
            org.bibletranslationtools.orature.plugins.launchPlugin(editor, take.file, pluginParams(chunk))
            endPluginOpen()
            onChunk(chunk)
        }
    }

    /** Cancel the waveform ticker + pause/release just the take player (its file is about to be
     *  handed to a plugin) and flip every "a plugin is open" flag on. The SOURCE player is
     *  deliberately kept alive so the plugin-opened cover can still offer source playback. Shared
     *  by [editTakeExternally] and [recordWithExternalPlugin]. */
    private fun beginPluginOpen() {
        waveformTickerJob?.cancel()
        runCatching { takePlayer?.pause(); takePlayer?.release() }
        takePlayer = null
        _uiState.value = _uiState.value.copy(isPluginOpen = true)
        translationVm.setPluginOpen(true)
        navigationLock.lock()
    }

    private fun endPluginOpen() {
        _uiState.value = _uiState.value.copy(isPluginOpen = false)
        translationVm.setPluginOpen(false)
        navigationLock.unlock()
    }

    /** Translation context handed to a plugin (JVM: PluginParameters). */
    private fun pluginParams(chunk: Chunk): org.bibletranslationtools.otter.common.domain.plugins.PluginParameters {
        val wb = workbookDataStore.activeWorkbook.value
        val chapter = workbookDataStore.activeChapter.value
        val sourceAudio = wb?.let { w ->
            chapter?.let { runCatching { w.sourceAudioAccessor.getChapter(it.sort, w.target)?.file }.getOrNull() }
        }
        return org.bibletranslationtools.otter.common.domain.plugins.PluginParameters(
            languageName = wb?.target?.language?.name ?: "",
            bookSlug = wb?.target?.slug ?: "",
            bookTitle = wb?.target?.title ?: (wb?.target?.slug ?: ""),
            chapterLabel = chapter?.title ?: chapter?.sort?.toString() ?: "",
            chapterNumber = chapter?.sort ?: 1,
            verseTotal = null,
            chunkNumber = chunk.sort,
            chunkLabel = chunk.sort.toString(),
            sourceChapterAudio = sourceAudio,
            sourceLanguageName = wb?.source?.language?.name
        )
    }

    fun onRecordNew() {
        val chunk = activeChunk ?: return
        if (selectedPlugin(recorder = true) != null) { recordWithExternalPlugin(chunk); return }
        stopAll()
        viewModelScope.launch {
            try {
                val take = withContext(Dispatchers.IO) { newTake(chunk) }
                pendingTake = take
                val rec = AudioRecorderConnection(RECORDER_ID, recorderFactory, viewModelScope)
                rec.start(AudioSpec())
                recorder = rec
                val takeAudio = OratureAudioFile(take.file, 1, DEFAULT_SAMPLE_RATE, 16)
                val w = WavFileWriter(takeAudio, rec.getAudioStream(), false, {}, viewModelScope)
                w.listen()
                writer = w
                activeRenderer = ActiveRecordingRenderer(
                    rec.getAudioStream(), recordingActiveFlow, RECORD_WIDTH, RECORD_SECONDS, viewModelScope
                )
                w.start()
                recordingActiveFlow.value = true
                _uiState.value = _uiState.value.copy(recording = true, recordingActive = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(recording = false, recordingActive = false, error = e.message)
            }
        }
    }

    fun toggleRecording() {
        val w = writer ?: return
        if (_uiState.value.recordingActive) {
            w.pause(); recordingActiveFlow.value = false
            _uiState.value = _uiState.value.copy(recordingActive = false)
        } else {
            w.start(); recordingActiveFlow.value = true
            _uiState.value = _uiState.value.copy(recordingActive = true)
        }
    }

    /** Keep the re-recording: finalize + insert (auto-selected); the new take is unchecked so the chunk
     *  needs confirming again (JVM: insertTake then re-navigate to PEER_EDIT). */
    fun saveRecording() {
        val chunk = activeChunk ?: return
        val take = pendingTake ?: return
        viewModelScope.launch {
            recordingActiveFlow.value = false
            withContext(Dispatchers.IO) {
                writer?.pause()
                writer?.closeAndJoin()
                runCatching { recorder?.stop() }
            }
            stopRecordingPipeline()
            val hasAudio = runCatching { OratureAudioFile(take.file).totalFrames > 0 }.getOrDefault(false)
            if (hasAudio) {
                chunk.audio.insertTake(take)
            } else {
                runCatching { take.file.delete() }
            }
            _uiState.value = _uiState.value.copy(recording = false, recordingActive = false)
            onChunk(chunk)
            translationVm.onChunkTakesChanged()
        }
    }

    fun cancelRecording() {
        val take = pendingTake
        viewModelScope.launch {
            recordingActiveFlow.value = false
            withContext(Dispatchers.IO) {
                writer?.pause()
                writer?.closeAndJoin()
                runCatching { recorder?.stop() }
            }
            stopRecordingPipeline()
            runCatching { take?.file?.delete() }
            _uiState.value = _uiState.value.copy(recording = false, recordingActive = false)
            activeChunk?.let { onChunk(it) }
        }
    }

    fun undo() {
        if (!actionHistory.canUndo()) return
        actionHistory.undo()
        translationVm.updateChunkUndoRedo(canUndo = actionHistory.canUndo(), canRedo = true)
        translationVm.onChunkTakesChanged()
    }

    fun redo() {
        if (!actionHistory.canRedo()) return
        actionHistory.redo()
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = actionHistory.canRedo())
        translationVm.onChunkTakesChanged()
    }

    private fun onUndoableAction() {
        translationVm.updateChunkUndoRedo(canUndo = true, canRedo = false)
    }

    /** Polls the take player for the playhead position + play/pause state. The target-take waveform
     *  is drawn by the shared renderer sampling the peak cache in the draw pass. */
    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val p = takePlayer
                val current = _uiState.value
                var playing = current.isPlaying
                if (p != null) {
                    runCatching {
                        playing = p.isPlaying()
                        if (playing) positionFrames = p.getLocationInFrames()
                    }.onFailure { System.err.println("[peeredit] take state poll failed: $it") }
                }
                if (current.isPlaying != playing) {
                    _uiState.value = _uiState.value.copy(isPlaying = playing)
                }
                delay(33)
            }
        }
    }

    /** Polls ONLY the source player, independent of [startWaveformTicker] — kept running even while
     *  [waveformTickerJob] is cancelled during an external-plugin edit (see [beginPluginOpen]), since
     *  the source player is deliberately left alive so the plugin-opened cover can still play it.
     *  Without this, the cover's play/pause icon and scrubber would freeze once the take-focused
     *  ticker stops. */
    private fun startSourceTicker() {
        sourceTickerJob?.cancel()
        sourceTickerJob = viewModelScope.launch(Dispatchers.Default) {
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

    private fun stopRecordingPipeline() {
        runCatching { activeRenderer?.close() }
        recorder = null
        writer = null
        activeRenderer = null
        pendingTake = null
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
        if (writer != null || recorder != null) {
            recordingActiveFlow.value = false
            runCatching { writer?.close() }
            runCatching { recorder?.stop() }
            stopRecordingPipeline()
        }
    }

    public override fun onCleared() {
        translationVm.clearUndoRedoHandlers()
        // Safety net: normal navigation is blocked while a plugin is open (see beginPluginOpen),
        // but don't leave the shell's navigation lock stuck on if this VM is ever cleared anyway.
        if (_uiState.value.isPluginOpen) {
            translationVm.setPluginOpen(false)
            navigationLock.unlock()
        }
        stopAll()
    }

    companion object {
        private const val SOURCE_PLAYER_ID = 90_020
        private const val TAKE_PLAYER_ID = 90_021
        private const val RECORDER_ID = 90_022
        // See OratureBlindDraftViewModel: kept wider than any real screen so the live-record
        // waveform down-samples to crisp 1px lines instead of up-sampling into fat blocks.
        private const val RECORD_WIDTH = 4096
        private const val RECORD_SECONDS = 10
    }
}
