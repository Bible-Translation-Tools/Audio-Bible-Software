package org.bibletranslationtools.otter.common.recorder

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveRecordingRendererTest {

    @Test
    fun rendererKeepsTenSecondWindowWithinRingBufferCapacity() = runTest {
        val width = 100
        val secondsOnScreen = 10
        val sampleRate = 44100
        val stream = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4096)
        val recording = MutableStateFlow(true)

        val renderer = ActiveRecordingRenderer(
            stream = stream,
            recordingStatus = recording,
            width = width,
            secondsOnScreen = secondsOnScreen,
            scope = this
        )

        // Ensure collector jobs are active.
        advanceUntilIdle()

        // Emit 12 seconds of low-frequency (2 Hz) sine to overfill the window.
        val totalSamples = sampleRate * 12
        val chunkSamples = 512
        var emitted = 0
        while (emitted < totalSamples) {
            val count = minOf(chunkSamples, totalSamples - emitted)
            stream.emit(sinePcm16le(startIndex = emitted, sampleCount = count, frequencyHz = 2.0, sampleRate = sampleRate))
            emitted += count
        }

        advanceUntilIdle()

        // Ring buffer should clamp to width * 2 (min,max per x pixel).
        assertEquals(width * 2, renderer.floatBuffer.size())
        assertTrue(renderer.floatBuffer.array.any { it != 0f }, "Expected non-zero waveform data")

        renderer.close()
    }

    private fun sinePcm16le(
        startIndex: Int,
        sampleCount: Int,
        frequencyHz: Double,
        sampleRate: Int
    ): ByteArray {
        val out = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val t = (startIndex + i).toDouble() / sampleRate.toDouble()
            val sample = (sin(2.0 * PI * frequencyHz * t) * Short.MAX_VALUE).roundToInt().toShort()
            val pos = i * 2
            out[pos] = (sample.toInt() and 0xFF).toByte()
            out[pos + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}

