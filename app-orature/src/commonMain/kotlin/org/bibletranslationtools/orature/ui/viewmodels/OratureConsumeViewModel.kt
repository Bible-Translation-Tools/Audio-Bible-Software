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
import org.bibletranslationtools.orature.ui.workbook.OratureWorkbookDataStore
import org.bibletranslationtools.orature.ui.workbook.PrecomputedWaveform
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.newaudio.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.newaudio.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val CONSUME_WAVEFORM_WIDTH = 960
private const val CONSUME_SECONDS_ON_SCREEN = 10

/** UI state for the Consume step (JVM: `ConsumeViewModel`). */
data class OratureConsumeUiState(
    val isLoading: Boolean = true,
    /** True when the source has no audio for this chapter (JVM: SourceAudioMissing). */
    val sourceMissing: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null
)

/**
 * Drives the Consume step: plays the chapter's SOURCE audio (read-only), rendering its waveform +
 * verse markers so the translator can listen and internalize the passage before drafting. Mirrors
 * the JVM `ConsumeViewModel`, but the waveform routes through our live renderer ([AudioReaderDrawable])
 * instead of the retired image-precompute `MarkerWaveform`, and playback uses the shared newaudio
 * player. Read-only: markers can't be moved or deleted here.
 */
class OratureConsumeViewModel(
    private val chapterSort: Int
) : ViewModel(), KoinComponent {

    private val workbookDataStore: OratureWorkbookDataStore by inject()
    private val playerFactory: AudioPlayerConnectionFactory by inject()

    private val _uiState = MutableStateFlow(OratureConsumeUiState())
    val uiState: StateFlow<OratureConsumeUiState> = _uiState.asStateFlow()

    private var player: IAudioPlayer? = null
    private var precomputed: PrecomputedWaveform? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var markerInfos: List<OratureMarkerInfo> = emptyList()
    private var waveformFront: FloatArray = FloatArray(CONSUME_WAVEFORM_WIDTH * 2)

    private var waveformTickerJob: Job? = null

    // Providers read by the Consume screen each display frame.
    fun currentWaveform(): FloatArray = waveformFront
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentMarkers(): List<OratureMarkerInfo> = markerInfos

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = OratureConsumeUiState(isLoading = true)
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val wb = workbookDataStore.activeWorkbook.value ?: error("No active workbook")
                    val sa = wb.sourceAudioAccessor.getChapter(chapterSort, wb.target)
                        ?: return@withContext null
                    val audioFile = OratureAudioFile(sa.file)
                    audioFile.clearCues()
                    val verseMarkers = audioFile.getMarker<VerseMarker>()
                    val playerReader = audioFile.reader().apply { open() }
                    val sr = playerReader.spec.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    // Decode the whole file's peaks ONCE (off the main thread); the ticker slices it.
                    val peakReader = audioFile.reader().apply { open() }
                    val peaks = try {
                        PrecomputedWaveform.build(peakReader, CONSUME_WAVEFORM_WIDTH, CONSUME_SECONDS_ON_SCREEN, sr)
                    } finally {
                        runCatching { peakReader.release() }
                    }
                    Prepared(playerReader, sr, peaks, verseMarkers)
                } ?: run {
                    _uiState.value = OratureConsumeUiState(isLoading = false, sourceMissing = true)
                    return@launch
                }

                sampleRate = prepared.sampleRate
                totalFrames = prepared.playerReader.totalFrames
                precomputed = prepared.peaks
                markerInfos = prepared.markers.mapIndexed { i, m ->
                    OratureMarkerInfo(verseIndex = i, location = m.location, label = m.label, movable = false)
                }

                val p = AudioPlayerConnection(PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
                p.load(prepared.playerReader)
                player = p

                _uiState.value = OratureConsumeUiState(isLoading = false)
                startWaveformTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = OratureConsumeUiState(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    private data class Prepared(
        val playerReader: AudioFileReader,
        val sampleRate: Int,
        val peaks: PrecomputedWaveform,
        val markers: List<VerseMarker>
    )

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying()) p.pause() else p.play()
        _uiState.value = _uiState.value.copy(isPlaying = p.isPlaying())
    }

    fun pause() {
        player?.pause()
        _uiState.value = _uiState.value.copy(isPlaying = false)
    }

    /** Seek to an absolute frame (scrollbar / scrub / marker jump). */
    fun seekToFrame(frame: Int) {
        val clamped = frame.coerceIn(0, totalFrames)
        player?.seek(clamped)
        positionFrames = clamped
    }

    /** Jump to the previous / next verse marker (JVM: seekPrevious/seekNext). */
    fun seekPrevious() {
        val target = markerInfos.map { it.location }.filter { it < positionFrames - 1 }.maxOrNull() ?: 0
        seekToFrame(target)
    }

    fun seekNext() {
        val target = markerInfos.map { it.location }.firstOrNull { it > positionFrames + 1 } ?: return
        seekToFrame(target)
    }

    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = viewModelScope.launch(Dispatchers.Default) {
            val out = FloatArray(CONSUME_WAVEFORM_WIDTH * 2)
            while (isActive) {
                val p = player
                val peaks = precomputed
                if (p != null && peaks != null) {
                    runCatching {
                        val playing = p.isPlaying()
                        if (playing) positionFrames = p.getLocationInFrames()
                        if (_uiState.value.isPlaying != playing) {
                            _uiState.value = _uiState.value.copy(isPlaying = playing)
                        }
                        // Playhead is drawn at center, so slice starting half a window before the
                        // position (negative frames zero-padded). Keeps the wave aligned with markers.
                        val halfWindow = CONSUME_SECONDS_ON_SCREEN * sampleRate / 2
                        peaks.window(positionFrames - halfWindow, out)
                        waveformFront = out.copyOf()
                    }.onFailure { System.err.println("[consume] waveform render failed: $it") }
                }
                delay(33)
            }
        }
    }

    public override fun onCleared() {
        waveformTickerJob?.cancel()
        runCatching { player?.pause() }
        runCatching { player?.release() }
        player = null
        precomputed = null
    }

    companion object {
        // A dedicated player-connection id (distinct from narration's incrementing ids).
        private const val PLAYER_ID = 90_001
    }
}
