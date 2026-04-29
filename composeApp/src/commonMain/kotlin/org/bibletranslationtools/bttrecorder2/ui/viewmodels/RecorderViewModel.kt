package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.audio.AudioFileFormat
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.bttrecorder2.ui.playback.SourceAudioPlayerController
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.Recordable
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer
import org.bibletranslationtools.otter.common.recorder.RecordingTimer
import org.bibletranslationtools.otter.common.recorder.WavFileWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

class RecorderViewModel(
    private val workbookRepository: IWorkbookRepository,
    private val audioRecorderFactory: AudioRecorderConnectionFactory,
    audioPlayerFactory: AudioPlayerConnectionFactory
) : ViewModel() {

    private val sourceAudioController = SourceAudioPlayerController(
        factory = audioPlayerFactory,
        scope = viewModelScope
    )

    val sourceAudioState: StateFlow<SourceAudioPlayerController.UiState> = sourceAudioController.uiState
    enum class RecordingUiState {
        Idle,
        Recording,
        Paused,
        Review
    }

    data class TargetUiState(
        val sourceLabel: String = "",
        val bookLabel: String = "",
        val chapterValue: String = "",
        val unitValue: String = "",
        val title: String = "",
        val subtitle: String = "",
        val canGoPreviousChapter: Boolean = false,
        val canGoNextChapter: Boolean = false,
        val canGoPreviousUnit: Boolean = false,
        val canGoNextUnit: Boolean = false
    )

    private data class RecordingTarget(
        val chapter: Chapter,
        val chunk: Chunk?
    ) {
        val recordable: Recordable
            get() = chunk ?: chapter
    }

    private var workbook: Workbook? = null
    private var targets: List<RecordingTarget> = emptyList()
    private var currentTargetIndex = -1

    private var associatedAudio: AssociatedAudio? = null
    private var targetDirectory: File? = null
    private var currentNamer: org.bibletranslationtools.otter.common.domain.content.FileNamer? = null

    private var wavFileWriter: WavFileWriter? = null
    private var currentTempAudioFile: File? = null
    private var recorderJob: Job? = null
    private var timerTickerJob: Job? = null
    private var volumeTickerJob: Job? = null
    private var takeStreamJob: Job? = null

    private val timer = RecordingTimer()
    private var hasRecordedAudio = false
    private var recorderInitialized = false

    private val _recordingState = MutableStateFlow(RecordingUiState.Idle)
    val recordingState: StateFlow<RecordingUiState> = _recordingState.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _timerText = MutableStateFlow("00:00:00")
    val timerText: StateFlow<String> = _timerText.asStateFlow()

    private val _volumeLevel = MutableStateFlow(0f)
    val volumeLevel: StateFlow<Float> = _volumeLevel.asStateFlow()

    private val _audioError = MutableStateFlow<String?>(null)
    val audioError: StateFlow<String?> = _audioError.asStateFlow()

    private val _waveformRenderer = MutableStateFlow<ActiveRecordingRenderer?>(null)
    val waveformRenderer: StateFlow<ActiveRecordingRenderer?> = _waveformRenderer.asStateFlow()

    private val _targetUi = MutableStateFlow(TargetUiState())
    val targetUi: StateFlow<TargetUiState> = _targetUi.asStateFlow()

    private val _savedTakeEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val savedTakeEvents: SharedFlow<Int> = _savedTakeEvents.asSharedFlow()

    fun loadTarget(
        sourceId: Int,
        targetId: Int,
        chapterNumber: Int,
        unitNumber: Int?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val projects = workbookRepository.getProjectsSuspend()
            val foundWorkbook = projects.find {
                it.source.collectionId == sourceId && it.target.collectionId == targetId 
            } ?: return@launch

            val chapterList = foundWorkbook.target.chapters.toList().await()
                .sortedBy { it.sort }

            if (chapterList.isEmpty()) return@launch

            val expandedTargets = mutableListOf<RecordingTarget>()
            chapterList.forEach { chapter ->
                expandedTargets.add(RecordingTarget(chapter = chapter, chunk = null))
                val chunks = chapter.chunksSuspend().sortedBy { it.sort }
                chunks.forEach { chunk ->
                    expandedTargets.add(RecordingTarget(chapter = chapter, chunk = chunk))
                }
            }

            val initialIndex = expandedTargets.indexOfFirst { target ->
                val chapterMatch = target.chapter.sort == chapterNumber
                if (!chapterMatch) return@indexOfFirst false
                if (unitNumber == null) {
                    target.chunk == null
                } else {
                    target.chunk?.sort == unitNumber
                }
            }

            val safeInitialIndex = if (initialIndex >= 0) initialIndex else 0

            workbook = foundWorkbook
            targets = expandedTargets
            switchToTarget(safeInitialIndex, force = true)
        }
    }

    fun goPreviousTarget() {
        val previousIndex = currentTargetIndex - 1
        if (canNavigateTo(previousIndex)) {
            switchToTarget(previousIndex)
        }
    }

    fun goNextTarget() {
        val nextIndex = currentTargetIndex + 1
        if (canNavigateTo(nextIndex)) {
            switchToTarget(nextIndex)
        }
    }

    fun goPreviousChapter() {
        if (_recordingState.value != RecordingUiState.Idle) return
        val current = currentTarget() ?: return
        val targetChunkSort = current.chunk?.sort
        val prevChapterSort = targets
            .map { it.chapter.sort }
            .distinct()
            .sorted()
            .lastOrNull { it < current.chapter.sort }
            ?: return

        val nextIndex = targets.indexOfFirst {
            it.chapter.sort == prevChapterSort && (
                if (targetChunkSort == null) it.chunk == null else it.chunk?.sort == targetChunkSort
            )
        }.let { idx ->
            if (idx >= 0) idx else targets.indexOfFirst { it.chapter.sort == prevChapterSort && it.chunk == null }
        }
        if (nextIndex >= 0) switchToTarget(nextIndex)
    }

    fun goNextChapter() {
        if (_recordingState.value != RecordingUiState.Idle) return
        val current = currentTarget() ?: return
        val targetChunkSort = current.chunk?.sort
        val nextChapterSort = targets
            .map { it.chapter.sort }
            .distinct()
            .sorted()
            .firstOrNull { it > current.chapter.sort }
            ?: return

        val nextIndex = targets.indexOfFirst {
            it.chapter.sort == nextChapterSort && (
                if (targetChunkSort == null) it.chunk == null else it.chunk?.sort == targetChunkSort
            )
        }.let { idx ->
            if (idx >= 0) idx else targets.indexOfFirst { it.chapter.sort == nextChapterSort && it.chunk == null }
        }
        if (nextIndex >= 0) switchToTarget(nextIndex)
    }

    fun goPreviousUnit() {
        if (_recordingState.value != RecordingUiState.Idle) return
        val current = currentTarget() ?: return
        val currentChunk = current.chunk ?: return
        val chapterChunkTargets = targets
            .withIndex()
            .filter { it.value.chapter.sort == current.chapter.sort && it.value.chunk != null }
            .sortedBy { it.value.chunk?.sort }
        val currentPos = chapterChunkTargets.indexOfFirst { it.value.chunk?.sort == currentChunk.sort }
        if (currentPos > 0) {
            switchToTarget(chapterChunkTargets[currentPos - 1].index)
        }
    }

    fun goNextUnit() {
        if (_recordingState.value != RecordingUiState.Idle) return
        val current = currentTarget() ?: return
        val currentChunk = current.chunk ?: return
        val chapterChunkTargets = targets
            .withIndex()
            .filter { it.value.chapter.sort == current.chapter.sort && it.value.chunk != null }
            .sortedBy { it.value.chunk?.sort }
        val currentPos = chapterChunkTargets.indexOfFirst { it.value.chunk?.sort == currentChunk.sort }
        if (currentPos >= 0 && currentPos < chapterChunkTargets.lastIndex) {
            switchToTarget(chapterChunkTargets[currentPos + 1].index)
        }
    }

    private fun currentTarget(): RecordingTarget? = targets.getOrNull(currentTargetIndex)

    private fun canNavigateTo(index: Int): Boolean {
        return index in targets.indices && _recordingState.value == RecordingUiState.Idle
    }

    private fun switchToTarget(index: Int, force: Boolean = false) {
        if (!force && !canNavigateTo(index)) return

        val wb = workbook ?: return
        val target = targets.getOrNull(index) ?: return
        currentTargetIndex = index

        associatedAudio = target.recordable.audio
        targetDirectory = wb.projectFilesAccessor.getChapterAudioDir(wb, target.chapter)
        currentNamer = WorkbookFileNamerBuilder.createFileNamer(
            workbook = wb,
            chapter = target.chapter,
            chunk = target.chunk,
            recordable = target.recordable,
            rcSlug = wb.sourceMetadataSlug
        )

        val title = if (target.chunk == null) {
            "Chapter ${target.chapter.label}"
        } else {
            "Unit ${target.chunk.label}"
        }
        val subtitle = "Chapter ${target.chapter.label}"
        val chapterValue = target.chapter.sort.toString()
        val unitValue = (target.chunk?.sort ?: 0).toString()
        val chapterSorts = targets.map { it.chapter.sort }.distinct().sorted()
        val chunkSortsInChapter = targets
            .mapNotNull { if (it.chapter.sort == target.chapter.sort) it.chunk?.sort else null }
            .distinct()
            .sorted()

        _targetUi.value = TargetUiState(
            sourceLabel = wb.source.resourceMetadata.identifier.uppercase(),
            bookLabel = wb.target.label,
            chapterValue = chapterValue,
            unitValue = unitValue,
            title = title,
            subtitle = subtitle,
            canGoPreviousChapter = chapterSorts.any { it < target.chapter.sort } && _recordingState.value == RecordingUiState.Idle,
            canGoNextChapter = chapterSorts.any { it > target.chapter.sort } && _recordingState.value == RecordingUiState.Idle,
            canGoPreviousUnit = target.chunk != null && chunkSortsInChapter.any { it < target.chunk.sort } && _recordingState.value == RecordingUiState.Idle,
            canGoNextUnit = target.chunk != null && chunkSortsInChapter.any { it > target.chunk.sort } && _recordingState.value == RecordingUiState.Idle
        )

        takeStreamJob?.cancel()
        takeStreamJob = viewModelScope.launch {
            associatedAudio?.takesFlow?.collect {
                // No-op: the flow keeps this target's take stream hot and up to date for future numbering.
            }
        }

        // Load source audio for the new target. Done off the IO dispatcher because
        // the accessor reads from disk (and possibly extracts from a Resource Container).
        viewModelScope.launch(Dispatchers.IO) {
            sourceAudioController.load(wb, target.chapter, target.chunk)
        }

        resetSessionForTarget()
    }

    fun toggleSourcePlayback() {
        sourceAudioController.togglePlayPause()
    }

    fun seekSourceToProgress(progress: Float) {
        sourceAudioController.seekToProgress(progress)
    }

    fun initializeAudio(width: Int) {
        val recorder = audioRecorderFactory.getRecorderWorker()

        val renderer = ActiveRecordingRenderer(
            recorder.audioStream,
            isRecording,
            width,
            10,
            viewModelScope
        )
        _waveformRenderer.value = renderer
        // Start volume monitor BEFORE starting recorder so we don't miss any
        // SharedFlow packets during the brief gap before our subscription registers.
        startVolumeMonitor()

        if (!recorderInitialized) {
            recorderJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    // Keep the recorder running while the screen is visible so the
                    // volume meter shows live mic input even before recording starts.
                    recorder.start(AudioSpec())
                    recorderInitialized = true
                    _audioError.value = null
                } catch (e: Exception) {
                    _audioError.value = e.message ?: "Unable to start recording device."
                }
            }
        }

        if (currentTempAudioFile == null) {
            setupTempWriter(recorder.audioStream)
        }
    }

    private fun setupTempWriter(audioStream: kotlinx.coroutines.flow.Flow<ByteArray>) {
        try {
            wavFileWriter?.close()
            currentTempAudioFile?.delete()

            val tempFile = File.createTempFile("rec_", ".wav")
            currentTempAudioFile = tempFile

            // Initialize with explicit format so a valid WAV header is created on first write.
            val oratureFile = OratureAudioFile(tempFile, 1, 44100, 16)

            wavFileWriter = WavFileWriter(
                oratureAudioFile = oratureFile,
                audioStream = audioStream,
                append = false,
                onComplete = { },
                scope = viewModelScope
            )
            wavFileWriter?.listen()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startRecording() {
        if (associatedAudio == null || _recordingState.value == RecordingUiState.Review || _audioError.value != null) return
        wavFileWriter?.start()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                audioRecorderFactory.getRecorderWorker().start(AudioSpec())
                withContext(Dispatchers.Main) {
                    _isRecording.value = true
                    _recordingState.value = RecordingUiState.Recording
                    hasRecordedAudio = true
                    timer.start()
                    startTimerTicker()
                    updateNavigationAvailability()
                    _audioError.value = null
                }
            } catch (e: Exception) {
                wavFileWriter?.pause()
                withContext(Dispatchers.Main) {
                    _audioError.value = e.message ?: "Unable to start recording device."
                }
            }
        }
    }

    fun pauseRecording() {
        if (_recordingState.value != RecordingUiState.Recording) return
        // Don't pause the recorder itself — keep mic live so the volume meter
        // still reflects what the user would be recording. Only pause the writer.
        _isRecording.value = false
        _recordingState.value = RecordingUiState.Paused
        wavFileWriter?.pause()
        timer.pause()
        updateNavigationAvailability()
    }

    fun resumeRecording() {
        if (_recordingState.value != RecordingUiState.Paused) return
        wavFileWriter?.start()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                audioRecorderFactory.getRecorderWorker().start(AudioSpec())
                withContext(Dispatchers.Main) {
                    _isRecording.value = true
                    _recordingState.value = RecordingUiState.Recording
                    timer.start()
                    startTimerTicker()
                    updateNavigationAvailability()
                    _audioError.value = null
                }
            } catch (e: Exception) {
                wavFileWriter?.pause()
                withContext(Dispatchers.Main) {
                    _audioError.value = e.message ?: "Unable to resume recording device."
                }
            }
        }
    }

    fun stopRecording() {
        if (_recordingState.value != RecordingUiState.Recording && _recordingState.value != RecordingUiState.Paused) return
        // Keep mic live so the volume meter continues to respond in Review state.
        _isRecording.value = false
        _recordingState.value = RecordingUiState.Review
        wavFileWriter?.pause()
        timer.pause()
        stopTimerTicker()
        updateNavigationAvailability()
    }

    fun saveRecording() {
        if (_recordingState.value != RecordingUiState.Review || !hasRecordedAudio) return
        val sessionFile = currentTempAudioFile ?: return
        val dir = targetDirectory ?: return
        val audio = associatedAudio ?: return
        val namer = currentNamer ?: return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Finalize WAV header before copying. Without this, saved takes can report 0 duration.
                wavFileWriter?.pause()
                wavFileWriter?.closeAndJoin()

                val capturedFrames = ensurePlayableSessionAudio(sessionFile)
                if (capturedFrames <= 0) {
                    throw IllegalStateException("Unable to finalize recording for save.")
                }

                val newTakeNumber = audio.getNewTakeNumberSuspend()
                val filename = namer.generateName(newTakeNumber, AudioFileFormat.WAV)
                val takeFile = File(dir, filename)
                val newTake = Take(
                    name = takeFile.name,
                    file = takeFile,
                    number = newTakeNumber,
                    format = MimeType.WAV,
                    createdTimestamp = LocalDate.now()
                )

                val stagedTakeFile = File(dir, "${filename}.staging.wav")
                runCatching { stagedTakeFile.delete() }
                sessionFile.copyTo(stagedTakeFile, overwrite = true)
                val stagedFrames = runCatching { OratureAudioFile(stagedTakeFile).totalFrames }.getOrDefault(0)
                if (stagedFrames <= 0) {
                    runCatching { stagedTakeFile.delete() }
                    throw IllegalStateException("Saved recording is invalid.")
                }
                commitStagedTake(stagedTakeFile, takeFile)

                withContext(Dispatchers.Main) {
                    audio.insertTake(newTake)
                    _savedTakeEvents.tryEmit(newTake.number)
                    resetSessionForTarget()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _audioError.value = e.message ?: "Unable to save recording."
            }
        }
    }

    private fun ensurePlayableSessionAudio(sessionFile: File): Int {
        var frames = runCatching { OratureAudioFile(sessionFile).totalFrames }.getOrDefault(0)
        if (frames > 0) return frames

        // Guarantee a parseable WAV so downstream loading never fails on invalid header.
        runCatching {
            if (sessionFile.length() < 44L) {
                val fresh = OratureAudioFile(sessionFile, 1, 44100, 16)
                fresh.writer(append = true, buffered = true).use { writer ->
                    writer.write(ByteArray(2))
                    writer.flush()
                }
            } else {
                OratureAudioFile(sessionFile).writer(append = true, buffered = true).use { writer ->
                    writer.write(ByteArray(2))
                    writer.flush()
                }
            }
        }

        frames = runCatching { OratureAudioFile(sessionFile).totalFrames }.getOrDefault(0)
        return frames
    }

    private fun commitStagedTake(stagedTakeFile: File, takeFile: File) {
        takeFile.parentFile?.mkdirs()
        runCatching {
            Files.move(
                stagedTakeFile.toPath(),
                takeFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure {
            stagedTakeFile.copyTo(takeFile, overwrite = true)
            runCatching { stagedTakeFile.delete() }
        }

        val committedFrames = runCatching { OratureAudioFile(takeFile).totalFrames }.getOrDefault(0)
        if (committedFrames <= 0) {
            runCatching { takeFile.delete() }
            throw IllegalStateException("Saved recording is invalid.")
        }
    }

    fun cancelRecording() {
        resetSessionForTarget()
    }

    private fun resetSessionForTarget() {
        _isRecording.value = false
        _recordingState.value = RecordingUiState.Idle
        hasRecordedAudio = false
        stopTimerTicker()
        timer.pause()
        timer.reset()
        _timerText.value = "00:00:00"
        _volumeLevel.value = 0f

        wavFileWriter?.pause()
        val recorder = audioRecorderFactory.getRecorderWorker()
        setupTempWriter(recorder.audioStream)
        _waveformRenderer.value?.clearData()
        updateNavigationAvailability()
    }

    private fun startTimerTicker() {
        if (timerTickerJob?.isActive == true) return
        timerTickerJob = viewModelScope.launch {
            while (_recordingState.value == RecordingUiState.Recording) {
                _timerText.value = formatElapsed(timer.timeElapsed)
                delay(100)
            }
        }
    }

    private fun stopTimerTicker() {
        timerTickerJob?.cancel()
        timerTickerJob = null
        _timerText.value = formatElapsed(timer.timeElapsed)
    }

    private fun startVolumeMonitor() {
        if (volumeTickerJob?.isActive == true) return
        val recorder = audioRecorderFactory.getRecorderWorker()
        // Read raw audio bytes from the SharedFlow and compute peak amplitude per packet.
        // SharedFlow allows multiple collectors, so this runs alongside the renderer and writer.
        volumeTickerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                recorder.audioStream.collect { bytes ->
                    var peak = 0
                    var i = 0
                    while (i + 1 < bytes.size) {
                        // 16-bit little-endian signed PCM
                        val low = bytes[i].toInt() and 0xFF
                        val high = bytes[i + 1].toInt()
                        val sample = (high shl 8) or low
                        val a = if (sample < 0) -sample else sample
                        if (a > peak) peak = a
                        i += 2
                    }
                    _volumeLevel.value = (peak / 32768f).coerceIn(0f, 1f)
                }
            } catch (_: Exception) {
                // ignore collector errors; cleanup() will cancel the job
            }
        }
    }

    private fun formatElapsed(ms: Long): String {
        return String.format(
            "%02d:%02d:%02d",
            ms / 3600000,
            (ms / 60000) % 60,
            (ms / 1000) % 60
        )
    }

    private fun updateNavigationAvailability() {
        val state = _targetUi.value
        val canNavigate = _recordingState.value == RecordingUiState.Idle
        val current = currentTarget()
        val currentChapterSort = current?.chapter?.sort
        val currentChunkSort = current?.chunk?.sort
        val chapterSorts = targets.map { it.chapter.sort }.distinct().sorted()
        val chunkSortsInChapter = targets
            .mapNotNull { if (it.chapter.sort == currentChapterSort) it.chunk?.sort else null }
            .distinct()
            .sorted()

        _targetUi.value = state.copy(
            canGoPreviousChapter = canNavigate && currentChapterSort != null && chapterSorts.any { it < currentChapterSort },
            canGoNextChapter = canNavigate && currentChapterSort != null && chapterSorts.any { it > currentChapterSort },
            canGoPreviousUnit = canNavigate && currentChunkSort != null && chunkSortsInChapter.any { it < currentChunkSort },
            canGoNextUnit = canNavigate && currentChunkSort != null && chunkSortsInChapter.any { it > currentChunkSort }
        )
    }

    fun cleanup() {
        _waveformRenderer.value?.close()
        _waveformRenderer.value = null
        wavFileWriter?.close()
        takeStreamJob?.cancel()
        stopTimerTicker()
        volumeTickerJob?.cancel()
        recorderJob?.cancel()
        sourceAudioController.release()
        viewModelScope.launch {
            try {
                audioRecorderFactory.getRecorderWorker().stop()
            } catch (e: Exception) {
                // Ignore
            }
        }
        currentTempAudioFile?.delete()
        recorderInitialized = false
    }
}
