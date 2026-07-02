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

    // AudioReaderDrawable compresses at this INTEGER frames-per-pixel. The UI must
    // use the same value (not the exact framesOnScreen/width) or the drawn audio
    // and the playhead/markers drift apart — imperceptibly at small widths, but
    // several pixels once the window is wide/maximized.
    val framesPerPixel = (framesOnScreen / width).coerceAtLeast(1)

    private val drawable = AudioReaderDrawable(
        audioReader = reader,
        width = width,
        secondsOnScreen = secondsOnScreen,
        recordingSampleRate = reader.spec.sampleRate
    )

    fun renderCentered(frame: Int): FloatArray {
        // Place `frame` on the exact middle pixel using framesPerPixel so it lines
        // up with the playhead drawn at width/2, regardless of the truncation.
        val start = frame - (width / 2) * framesPerPixel
        return drawable.getWaveformDrawable(start).copyOf()
    }

    fun close() {
        reader.release()
    }
}
