package org.bibletranslationtools.otter.common.recorder

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ActiveRecordingRendererTest {

    @Test
    fun rendererKeepsTenSecondWindowWithinRingBufferCapacity() = runBlocking {
        val width = 100
        val secondsOnScreen = 10
        val sampleRate = 44100
        // Keep enough replayed chunks so the renderer receives the full stream
        // even if collection starts slightly after emissions begin.
        val stream = MutableSharedFlow<ByteArray>(replay = 2048, extraBufferCapacity = 4096)
        val recording = MutableStateFlow(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val renderer = ActiveRecordingRenderer(
            stream = stream,
            recordingStatus = recording,
            width = width,
            secondsOnScreen = secondsOnScreen,
            scope = scope
        )

        try {
            delay(50)

            // Emit 12 seconds of low-frequency (2 Hz) sine to overfill the window.
            val totalSamples = sampleRate * 12
            val chunkSamples = 512
            var emitted = 0
            while (emitted < totalSamples) {
                val count = minOf(chunkSamples, totalSamples - emitted)
                stream.tryEmit(
                    sinePcm16le(
                        startIndex = emitted,
                        sampleCount = count,
                        frequencyHz = 2.0,
                        sampleRate = sampleRate
                    )
                )
                emitted += count
            }

            eventually(timeoutMs = 4000) {
                renderer.floatBuffer.size() == width * 2
            }

            // Ring buffer should clamp to width * 2 (min,max per x pixel).
            assertEquals(width * 2, renderer.floatBuffer.size())
            assertTrue(renderer.floatBuffer.array.any { it != 0f }, "Expected non-zero waveform data")
        } finally {
            renderer.close()
            scope.cancel()
        }
    }

    private suspend fun eventually(timeoutMs: Long, condition: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            if (condition()) return
            delay(20)
        }
        fail("Condition was not met within ${timeoutMs}ms")
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
