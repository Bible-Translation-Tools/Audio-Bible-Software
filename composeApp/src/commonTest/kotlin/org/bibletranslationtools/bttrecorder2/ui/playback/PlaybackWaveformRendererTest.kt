package org.bibletranslationtools.bttrecorder2.ui.playback

import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import org.bibletranslationtools.otter.common.device.newaudio.AudioSpec
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackWaveformRendererTest {

    @Test
    fun rendererProducesDrawableWithExpectedSizeAndSignal() {
        val width = 120
        val reader = SineAudioFileReader(totalFrames = 44100 * 3)
        reader.open()
        val renderer = PlaybackWaveformRenderer(reader = reader, width = width, secondsOnScreen = 10)

        val samples = renderer.renderCentered(frame = 44100)

        assertEquals(width * 2, samples.size)
        assertTrue(samples.any { it != 0f }, "Expected non-zero waveform values")

        renderer.close()
    }

    @Test
    fun rendererPadsWhenViewportStartsBeforeZero() {
        val width = 100
        val reader = SineAudioFileReader(totalFrames = 44100)
        reader.open()
        val renderer = PlaybackWaveformRenderer(reader = reader, width = width, secondsOnScreen = 10)

        val samples = renderer.renderCentered(frame = 0)

        assertEquals(width * 2, samples.size)
        assertTrue(samples.take(width / 2).any { it == 0f }, "Expected zero padding at start")

        renderer.close()
    }
}

private class SineAudioFileReader(
    override val totalFrames: Int,
    override val spec: AudioSpec = AudioSpec(sampleRate = 44100, bitDepth = 16, channels = 1)
) : AudioFileReader {
    override var framePosition: Int = 0
    private var opened = false

    override fun hasRemaining(): Boolean = opened && framePosition < totalFrames

    override fun getPcmBuffer(bytes: ByteArray): Int {
        if (!opened || !hasRemaining()) return 0

        val framesRequested = bytes.size / spec.bytesPerFrame
        val framesToRead = minOf(framesRequested, totalFrames - framePosition)
        val sampleRate = spec.sampleRate.toDouble()
        val frequency = 2.0

        var writePos = 0
        for (i in 0 until framesToRead) {
            val t = (framePosition + i).toDouble() / sampleRate
            val sample = (sin(2.0 * PI * frequency * t) * Short.MAX_VALUE).roundToInt().toShort()
            bytes[writePos] = (sample.toInt() and 0xFF).toByte()
            bytes[writePos + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            writePos += 2
        }

        framePosition += framesToRead
        return framesToRead * spec.bytesPerFrame
    }

    override fun seek(frame: Long) {
        framePosition = frame.toInt().coerceIn(0, totalFrames)
    }

    override fun open() {
        opened = true
    }

    override fun release() {
        opened = false
    }
}
