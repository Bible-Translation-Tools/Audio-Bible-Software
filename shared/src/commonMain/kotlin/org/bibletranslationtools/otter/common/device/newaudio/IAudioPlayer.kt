package org.bibletranslationtools.otter.common.device.newaudio

import kotlinx.coroutines.flow.Flow

interface IAudioPlayer {
    val frameStart: Int
    val frameEnd: Int

    // Replaces the Listener pattern with a reactive Flow of events
    val events: Flow<AudioPlayerEvent>

    fun load(reader: AudioFileReader)
    // Note: On Android, 'File' behaves differently.
    // We typically prefer passing the Reader directly in KMP.

    fun loadSection(reader: AudioFileReader, frameStart: Int, frameEnd: Int)

    fun getAudioReader(): AudioFileReader?
    fun changeRate(rate: Double)
    fun play()
    fun pause()
    fun toggle()
    fun stop()
    fun close()
    fun release()
    fun seek(position: Int)
    fun isPlaying(): Boolean
    fun getDurationInFrames(): Int
    fun getDurationMs(): Int
    fun getLocationInFrames(): Int
    fun getLocationMs(): Int

    /**
     * True when [getLocationInFrames] reflects the audible position. During playback
     * start / seek transients the underlying sink may not be running yet, in which
     * case the reported position is the WRITE cursor (ahead of what's heard) and
     * consumers smoothing the position (the display clock) should skip corrections.
     */
    fun isPositionReliable(): Boolean = true
}