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
import org.bibletranslationtools.shared.ui.playback.AudioTimeline
import org.bibletranslationtools.shared.ui.playback.FilePcmSource
import org.bibletranslationtools.shared.ui.playback.PcmSource
import org.bibletranslationtools.shared.ui.playback.WaveformPeakCache
import org.bibletranslationtools.shared.ui.playback.buildPeakCache
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.device.AudioFileReader
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.shared.ui.playback.PlaybackDisplayClock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


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
 * instead of the retired image-precompute `MarkerWaveform`, and playback uses the shared audio
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
    // Shared waveform engine (see OratureChapterReviewViewModel): single-segment timeline + an
    // in-memory peak cache filled once off-thread; the draw samples it per pixel.
    private var timeline: AudioTimeline? = null
    private var peakCache: WaveformPeakCache? = null
    private var peakSource: PcmSource? = null
    private var peakBuildJob: Job? = null
    // Rate-locked display clock (see OratureChapterReviewViewModel) — the screen advances it each
    // display frame so the waveform scrolls smoothly instead of in the ticker's 30 fps steps.
    val clock = PlaybackDisplayClock(
        positionSource = { player?.getLocationInFrames()?.toLong() ?: 0L },
        positionReliable = { player?.isPositionReliable() ?: false }
    )
    private var clockEventsJob: Job? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var totalFrames: Int = 0
    private var positionFrames: Int = 0
    private var markerInfos: List<OratureMarkerInfo> = emptyList()

    private var waveformTickerJob: Job? = null

    // Providers read by the Consume screen each display frame.
    fun currentTimeline(): AudioTimeline? = timeline
    fun peakCacheFor(source: PcmSource): WaveformPeakCache? =
        if (source.id == peakSource?.id) peakCache else null
    fun waveformSampleRate(): Int = sampleRate
    fun currentPosition(): Int = positionFrames
    fun currentTotalFrames(): Int = totalFrames
    fun currentMarkers(): List<OratureMarkerInfo> = markerInfos

    init {
        load()
    }

    private fun load() {
        launchLogged {
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
                    // Single-segment timeline + empty peak cache for the shared renderer (filled
                    // off-thread below); the draw samples it per pixel, no per-tick decode.
                    val source = FilePcmSource(sa.file)
                    val tl = AudioTimeline.ofWholeSource(source)
                    val cache = WaveformPeakCache(source.totalFrames)
                    Prepared(playerReader, sr, source, tl, cache, verseMarkers)
                } ?: run {
                    _uiState.value = OratureConsumeUiState(isLoading = false, sourceMissing = true)
                    return@launchLogged
                }

                sampleRate = prepared.sampleRate
                totalFrames = prepared.playerReader.totalFrames
                timeline = prepared.timeline
                peakCache = prepared.cache
                peakSource = prepared.source
                peakBuildJob?.cancel()
                peakBuildJob = launchLogged(Dispatchers.IO) {
                    runCatching { buildPeakCache(prepared.source, prepared.cache) }
                }
                markerInfos = prepared.markers.mapIndexed { i, m ->
                    OratureMarkerInfo(verseIndex = i, location = m.location, label = m.label, movable = false)
                }

                val p = AudioPlayerConnection(PLAYER_ID, playerFactory, viewModelScope, Dispatchers.Default)
                p.load(prepared.playerReader)
                player = p
                clock.sampleRate = sampleRate
                clock.durationFrames = totalFrames.toLong()
                clock.advancing = false
                clock.snapTo(0L)
                observePlayerForClock(p)

                _uiState.value = OratureConsumeUiState(isLoading = false)
                startWaveformTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("loading the consume screen", e)
                _uiState.value = OratureConsumeUiState(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    private data class Prepared(
        val playerReader: AudioFileReader,
        val sampleRate: Int,
        val source: PcmSource,
        val timeline: AudioTimeline,
        val cache: WaveformPeakCache,
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
        clock.snapTo(clamped.toLong())
    }

    /** Drive the display clock from the player's transport events (main thread). */
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

    /** Jump to the previous / next verse marker (JVM: seekPrevious/seekNext). */
    fun seekPrevious() {
        val target = markerInfos.map { it.location }.filter { it < positionFrames - 1 }.maxOrNull() ?: 0
        seekToFrame(target)
    }

    fun seekNext() {
        val target = markerInfos.map { it.location }.firstOrNull { it > positionFrames + 1 } ?: return
        seekToFrame(target)
    }

    /** Polls the player for the playhead position + play/pause state. The waveform itself is drawn
     *  by the shared renderer sampling the peak cache in the draw pass, so this no longer computes
     *  or allocates a waveform window each tick. */
    private fun startWaveformTicker() {
        waveformTickerJob?.cancel()
        waveformTickerJob = launchLogged(Dispatchers.Default) {
            while (isActive) {
                val p = player
                if (p != null) {
                    runCatching {
                        val playing = p.isPlaying()
                        if (playing) positionFrames = p.getLocationInFrames()
                        if (_uiState.value.isPlaying != playing) {
                            _uiState.value = _uiState.value.copy(isPlaying = playing)
                        }
                    }.onFailure { System.err.println("[consume] player state poll failed: $it") }
                }
                delay(33)
            }
        }
    }

    public override fun onCleared() {
        waveformTickerJob?.cancel()
        peakBuildJob?.cancel()
        clockEventsJob?.cancel()
        clock.advancing = false
        runCatching { player?.pause() }
        runCatching { player?.release() }
        player = null
        timeline = null
        peakCache = null
        peakSource = null
    }

    companion object {
        // A dedicated player-connection id (distinct from narration's incrementing ids).
        private const val PLAYER_ID = 90_001
    }
}
