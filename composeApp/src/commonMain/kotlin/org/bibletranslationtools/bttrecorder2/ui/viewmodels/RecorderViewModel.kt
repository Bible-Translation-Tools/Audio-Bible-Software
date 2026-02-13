package org.bibletranslationtools.bttrecorder2.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.rx2.awaitFirst
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.repositories.IWorkbookRepository
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.DateHolder
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.newaudio.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer
import org.bibletranslationtools.otter.common.recorder.WavFileWriter
import com.jakewharton.rxrelay2.BehaviorRelay
import java.io.File
import kotlin.math.max

class RecorderViewModel(
    private val workbookRepository: IWorkbookRepository,
    private val audioRecorderFactory: AudioRecorderConnectionFactory
) : ViewModel() {
    // Target
    private var associatedAudio: AssociatedAudio? = null
    private var workbook: Workbook? = null
    private var chapter: Chapter? = null
    
    // State
    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()
    
    private val _waveformRenderer = MutableStateFlow<ActiveRecordingRenderer?>(null)
    val waveformRenderer = _waveformRenderer.asStateFlow()

    private val _targetName = MutableStateFlow("")
    val targetName = _targetName.asStateFlow()

    // Internal
    private var wavFileWriter: WavFileWriter? = null
    private var currentAudioFile: File? = null
    private var maxTakeNumber = 0
    private var targetDirectory: File? = null
    private var recorderJob: Job? = null

    // Load Target
    fun loadTarget(
        sourceId: Int,
        targetId: Int,
        chapterNumber: Int,
        unitNumber: Int?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val projects = workbookRepository.getProjectsSuspend()
            val workbook = projects.find { 
                it.source.collectionId == sourceId && it.target.collectionId == targetId 
            } ?: return@launch

            // Find Chapter
            val chapter = workbook.target.chapters
                .filter { (it.sort ?: 0) == chapterNumber }
                .awaitFirst()

            this@RecorderViewModel.workbook = workbook
            this@RecorderViewModel.chapter = chapter

            // Get directory using accessor
            val chapterDir = workbook.projectFilesAccessor.getChapterAudioDir(workbook, chapter)

            if (unitNumber == null) {
                // Recording Meta Chunk (Chapter)
                associatedAudio = chapter.audio
                targetDirectory = chapterDir
                _targetName.value = "Chapter ${chapter.label}"
            } else {
                // Recording Unit (Chunk)
                val chunks = chapter.chunksSuspend()
                val chunk = chunks.find { (it.sort ?: 0) == unitNumber }
                
                if (chunk != null) {
                    associatedAudio = chunk.audio
                    // Units usually share chapter audio directory or have subfolder?
                    // Assuming chapter directory for now as per legacy usually.
                    targetDirectory = chapterDir
                    _targetName.value = "Unit ${chunk.label}"
                }
            }

            // Subscribe to takes to update maxTakeNumber
            // associatedAudio?.takes is ReplayRelay<Take>
            // We can subscribe using RxJava or convert to Flow
            // Using RxJava subscribe inside IO scope (careful with disposal, but for ViewModel lifecycle it might leak if not disposed)
            // Better to use asFlow() and collect
            val audio = associatedAudio
            if (audio != null) {
                 launch {
                     audio.takes.asFlow().collect { take ->
                         maxTakeNumber = max(maxTakeNumber, take.number)
                     }
                 }
            }
        }
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
        
        recorderJob = viewModelScope.launch {
            recorder.start(AudioSpec())
        }
        
        setupWavWriter(recorder.audioStream)
    }

    private fun setupWavWriter(audioStream: kotlinx.coroutines.flow.Flow<ByteArray>) {
        try {
            // Use a temporary file for the recording session
            // We need a temp directory.
            // java.io.File.createTempFile uses system temp.
            // This is okay for recording session.
            val tempFile = File.createTempFile("rec_", ".wav") 
            currentAudioFile = tempFile
            
            // `OratureAudioFile` init
            val oratureFile = OratureAudioFile(tempFile)
            
            wavFileWriter = WavFileWriter(
                oratureAudioFile = oratureFile,
                audioStream = audioStream,
                append = false,
                onComplete = { 
                    // File closed
                },
                scope = viewModelScope
            )
            wavFileWriter?.listen()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startRecording() {
        if (associatedAudio == null) return
        _isRecording.value = true
        wavFileWriter?.start()
    }

    fun stopRecording() {
        _isRecording.value = false
        wavFileWriter?.pause()
        
        // Save logic
        val file = currentAudioFile ?: return
        val dir = targetDirectory ?: return
        
        val newTakeNumber = maxTakeNumber + 1
        
        // Move file to target location?
        // Or create Take pointing to temp file and let something else move it?
        // Traditionally Orature moves or copies it.
        // For now, we will copy it to the target directory.
        val targetFile = File(dir, "take_$newTakeNumber.wav")
        try {
            file.copyTo(targetFile, overwrite = true)
            file.delete() // Delete temp
        } catch (e: Exception) {
            e.printStackTrace()
            // If copy fails, we still have temp file.
            // Proceed with temp file? No, Take needs persistent path.
            return
        }

        val now = java.time.LocalDate.now()
        
        val newTake = Take(
            name = targetFile.name,
            file = targetFile,
            number = newTakeNumber,
            format = MimeType.WAV,
            createdTimestamp = now,
            deletedTimestamp = BehaviorRelay.createDefault(DateHolder(null)),
            checkingState = BehaviorRelay.createDefault(TakeCheckingState(CheckingStatus.UNCHECKED))
        )
        
        // Emit to repository to persist
        associatedAudio?.insertTake(newTake)
        
        // Re-setup writer for next take
        wavFileWriter?.close()
        val recorder = audioRecorderFactory.getRecorderWorker()
        setupWavWriter(recorder.audioStream)
    }
    
    fun cleanup() {
        _waveformRenderer.value?.close()
        wavFileWriter?.close()
        recorderJob?.cancel()
        viewModelScope.launch {
             try {
                 audioRecorderFactory.getRecorderWorker().stop()
             } catch (e: Exception) {
                 // Ignore
             }
        }
        // Cleanup temp file if exists and not saved?
        currentAudioFile?.delete()
    }
}
