package org.bibletranslationtools.shared.ui.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioPlayerEvent
import org.bibletranslationtools.otter.common.device.IAudioPlayer
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceAudio

/**
 * Encapsulates a per-screen player for source (reference) audio so the Recorder and
 * Playback screens can share the same loading + UI state logic.
 *
 * The controller owns its own [IAudioPlayer] (one section at a time) and a
 * [StateFlow] of UI state. Callers feed it with a [Workbook] and a target
 * (chapter or chunk) and the controller resolves the right slice of source
 * audio via [Workbook.sourceAudioAccessor], then loads it via
 * [IAudioPlayer.loadSection] so playback is bounded to that verse / chunk /
 * chapter exactly the way Orature does it.
 *
 * No work happens until [load] is called. [release] must be called to free
 * the underlying audio resource when the screen is destroyed.
 */
class SourceAudioPlayerController(
    private val factory: AudioPlayerConnectionFactory,
    private val scope: CoroutineScope
) {
    data class UiState(
        val available: Boolean = false,
        val isPlaying: Boolean = false,
        val progress: Float = 0f,
        val elapsedText: String = "00:00:00",
        val durationText: String = "00:00:00"
    )

    private val playerId = kotlin.random.Random.nextInt()
    private val player: IAudioPlayer = AudioPlayerConnection(
        id = playerId,
        factory = factory,
        scope = scope,
        controlDispatcher = Dispatchers.Default
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var tickerJob: Job? = null
    private var currentSection: SourceAudio? = null

    init {
        // The shared player worker broadcasts events globally across every active
        // connection that uses it (e.g., the main playback player and the source
        // player share the same hardware sink). Reacting to those events directly
        // would let the source UI flip when the *main* player plays/pauses.
        // Instead, we drive UI state from a periodic poll of `player.isPlaying()`
        // — which is filtered by active-connection ID inside [AudioPlayerConnection]
        // and only reports true when *this* controller's player owns the hardware.
        // The events flow is still useful as a wakeup hint to recompute state.
        scope.launch {
            player.events.collect { _ -> refreshStateNow() }
        }
    }

    private fun refreshStateNow() {
        val playing = try { player.isPlaying() } catch (_: Exception) { false }
        if (playing) {
            _uiState.update { it.copy(isPlaying = true) }
            startTicker()
        } else {
            stopTicker()
            val durationMs = try { player.getDurationMs().coerceAtLeast(0) } catch (_: Exception) { 0 }
            val positionMs = try { player.getLocationMs().coerceAtLeast(0) } catch (_: Exception) { 0 }
            val progress = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else 0f
            _uiState.update {
                it.copy(
                    isPlaying = false,
                    progress = progress,
                    elapsedText = formatTime(positionMs),
                    durationText = formatTime(durationMs)
                )
            }
        }
    }

    /**
     * Resolves source audio for the given target and (re)loads the player to it.
     * Returns true when source audio is available and was loaded.
     */
    fun load(workbook: Workbook, chapter: Chapter, chunk: Chunk?): Boolean {
        // Stop any previous playback before swapping sections.
        if (player.isPlaying()) player.pause()

        val accessor = workbook.sourceAudioAccessor
        val source = try {
            if (chunk != null) {
                // For verse / chunk recordings, the accessor first tries chunk
                // markers in the user-imported chapter audio and falls back to
                // verse markers in the RC-provided chapter audio.
                accessor.getChunk(
                    chapter.sort,
                    chunk.sort,
                    chunk.start,
                    workbook.target
                )
            } else {
                // For chapter-level recordings, prefer user-imported audio (which
                // may be a freshly imported file with no markers yet) and fall
                // back to chapter audio inside the source RC.
                accessor.getUserMarkedChapter(chapter.sort, workbook.target)
                    ?: accessor.getChapter(chapter.sort, workbook.target)
            }
        } catch (_: Exception) {
            null
        }

        currentSection = source
        if (source == null) {
            _uiState.value = UiState(available = false)
            return false
        }

        return try {
            val reader = OratureAudioFile(source.file).reader()
            player.loadSection(reader, source.start, source.end)
            _uiState.update {
                UiState(
                    available = true,
                    isPlaying = false,
                    progress = 0f,
                    elapsedText = "00:00:00",
                    durationText = formatTime(player.getDurationMs())
                )
            }
            true
        } catch (_: Exception) {
            _uiState.value = UiState(available = false)
            false
        }
    }

    fun togglePlayPause() {
        if (!_uiState.value.available) return
        if (player.isPlaying()) player.pause() else player.play()
    }

    fun seekToProgress(progress: Float) {
        if (!_uiState.value.available) return
        val durationFrames = player.getDurationInFrames().takeIf { it > 0 } ?: return
        val target = (progress.coerceIn(0f, 1f) * durationFrames).toInt()
        player.seek(target)
        _uiState.update {
            it.copy(
                progress = progress.coerceIn(0f, 1f),
                elapsedText = formatTime(player.getLocationMs())
            )
        }
    }

    fun release() {
        stopTicker()
        try {
            player.release()
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val playing = try { player.isPlaying() } catch (_: Exception) { false }
                val durationMs = try { player.getDurationMs().coerceAtLeast(0) } catch (_: Exception) { 0 }
                val positionMs = try { player.getLocationMs().coerceAtLeast(0) } catch (_: Exception) { 0 }
                val progress = if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f
                _uiState.update {
                    it.copy(
                        isPlaying = playing,
                        progress = progress,
                        elapsedText = formatTime(positionMs),
                        durationText = formatTime(durationMs)
                    )
                }
                if (!playing) {
                    // Once we stop being the active player, drop out of the ticker
                    // loop until we play again.
                    break
                }
                delay(100)
            }
            tickerJob = null
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}
