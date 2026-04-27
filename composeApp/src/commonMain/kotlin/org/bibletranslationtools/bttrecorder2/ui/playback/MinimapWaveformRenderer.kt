package org.bibletranslationtools.bttrecorder2.ui.playback

import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.domain.narration.AudioReaderDrawable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a compressed waveform of the full audio duration at a given pixel width.
 * Used to draw the minimap overview.
 */
class MinimapWaveformRenderer(
    private val reader: AudioFileReader,
    private val width: Int
) {
    private val sampleRate = reader.spec.sampleRate
    private val totalFrames = reader.totalFrames
    // Map full duration onto 'width' pixels using AudioReaderDrawable's secondsOnScreen contract.
    private val totalSeconds = max(1, (totalFrames.toFloat() / sampleRate).roundToInt())

    private val drawable = AudioReaderDrawable(
        audioReader = reader,
        width = width,
        secondsOnScreen = totalSeconds,
        recordingSampleRate = sampleRate
    )

    fun render(): FloatArray = drawable.getWaveformDrawable(0).copyOf()

    fun close() {
        reader.release()
    }
}
