package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.content.Recordable
import org.bibletranslationtools.otter.common.domain.content.WorkbookFileNamerBuilder
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer
import org.bibletranslationtools.otter.common.recorder.RecordingTimer
import org.bibletranslationtools.otter.common.recorder.WavFileWriter
import java.io.File
import java.time.LocalDate

class RecorderViewModel(
    private val workbookRepository: IWorkbookRepository,
    private val audioRecorderFactory: AudioRecorderConnectionFactory
) : ViewModel() {
    enum class RecordingUiState {
        Idle,
        Recording,
        Paused,
        Review
    }

    data class TargetUiState(
        val title: String = "",
        val subtitle: String = "",
        val canGoPrevious: Boolean = false,
        val canGoNext: Boolean = false
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

    private val _waveformRenderer = MutableStateFlow<ActiveRecordingRenderer?>(null)
    val waveformRenderer: StateFlow<ActiveRecordingRenderer?> = _waveformRenderer.asStateFlow()

    private val _targetUi = MutableStateFlow(TargetUiState())
    val targetUi: StateFlow<TargetUiState> = _targetUi.asStateFlow()

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
        _targetUi.value = TargetUiState(
            title = title,
            subtitle = subtitle,
            canGoPrevious = index > 0 && _recordingState.value == RecordingUiState.Idle,
            canGoNext = index < targets.lastIndex && _recordingState.value == RecordingUiState.Idle
        )

        takeStreamJob?.cancel()
        takeStreamJob = viewModelScope.launch {
            associatedAudio?.takesFlow?.collect {
                // No-op: the flow keeps this target's take stream hot and up to date for future numbering.
            }
        }

        resetSessionForTarget()
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

        if (!recorderInitialized) {
            recorderJob = viewModelScope.launch(Dispatchers.IO) {
                recorder.start(AudioSpec())
                recorderInitialized = true
            }
            recorderInitialized = true
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

            val oratureFile = OratureAudioFile(tempFile)

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
        if (associatedAudio == null || _recordingState.value == RecordingUiState.Review) return
        _isRecording.value = true
        _recordingState.value = RecordingUiState.Recording
        hasRecordedAudio = true
        timer.start()
        startTimerTicker()
        wavFileWriter?.start()
        updateNavigationAvailability()
    }

    fun pauseRecording() {
        if (_recordingState.value != RecordingUiState.Recording) return
        _isRecording.value = false
        _recordingState.value = RecordingUiState.Paused
        wavFileWriter?.pause()
        timer.pause()
        updateNavigationAvailability()
    }

    fun resumeRecording() {
        if (_recordingState.value != RecordingUiState.Paused) return
        _isRecording.value = true
        _recordingState.value = RecordingUiState.Recording
        timer.start()
        startTimerTicker()
        wavFileWriter?.start()
        updateNavigationAvailability()
    }

    fun stopRecording() {
        if (_recordingState.value != RecordingUiState.Recording && _recordingState.value != RecordingUiState.Paused) return
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

                sessionFile.copyTo(newTake.file, overwrite = true)
                withContext(Dispatchers.Main) {
                    audio.insertTake(newTake)
                    resetSessionForTarget()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        _targetUi.value = state.copy(
            canGoPrevious = canNavigate && currentTargetIndex > 0,
            canGoNext = canNavigate && currentTargetIndex < targets.lastIndex
        )
    }

    fun cleanup() {
        _waveformRenderer.value?.close()
        _waveformRenderer.value = null
        wavFileWriter?.close()
        takeStreamJob?.cancel()
        stopTimerTicker()
        recorderJob?.cancel()
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
