package org.bibletranslationtools.bttrecorder2.ui.playback

import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.domain.narration.AudioReaderDrawable

/**
 * Renders a 10-second window centered on the given frame.
 */
class PlaybackWaveformRenderer(
    private val reader: AudioFileReader,
    private val width: Int,
    private val secondsOnScreen: Int = 10
) {
    private val framesOnScreen = reader.spec.sampleRate * secondsOnScreen
    private val drawable = AudioReaderDrawable(
        audioReader = reader,
        width = width,
        secondsOnScreen = secondsOnScreen,
        recordingSampleRate = reader.spec.sampleRate
    )

    fun renderCentered(frame: Int): FloatArray {
        val start = frame - (framesOnScreen / 2)
        return drawable.getWaveformDrawable(start).copyOf()
    }

    fun close() {
        reader.release()
    }
}
