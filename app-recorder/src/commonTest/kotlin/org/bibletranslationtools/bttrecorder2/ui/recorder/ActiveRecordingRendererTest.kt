package org.bibletranslationtools.bttrecorder2.ui.recorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ActiveRecordingRendererTest {

    @Test
    fun rendererIgnoresIncomingAudioWhenRecordingIsDisabled() = runBlocking {
        val stream = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
        val recording = MutableStateFlow(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val renderer = ActiveRecordingRenderer(stream, recording, width = 120, secondsOnScreen = 10, scope = scope)

        try {
            delay(50)
            repeat(60) { chunk ->
                stream.tryEmit(sineChunk(startFrame = chunk * 256, frames = 256))
            }

            delay(100)
            assertTrue(
                renderer.floatBuffer.array.all { it == 0f },
                "Buffer should stay empty while recording is disabled"
            )
        } finally {
            renderer.close()
            scope.cancel()
        }
    }

    @Test
    fun rendererPopulatesWaveformFromSineAudioWhenRecordingEnabled() = runBlocking {
        val stream = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val recording = MutableStateFlow(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val renderer = ActiveRecordingRenderer(stream, recording, width = 120, secondsOnScreen = 10, scope = scope)

        try {
            delay(50)
            repeat(120) { chunk ->
                stream.tryEmit(sineChunk(startFrame = chunk * 256, frames = 256))
            }

            eventually(timeoutMs = 1500) {
                renderer.floatBuffer.array.any { it != 0f }
            }
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
}

private fun sineChunk(
    startFrame: Int,
    frames: Int,
    frequencyHz: Double = 2.0,
    sampleRate: Int = 44_100,
    amplitude: Double = 0.6
): ByteArray {
    val out = ByteArray(frames * 2)
    var index = 0
    for (i in 0 until frames) {
        val t = (startFrame + i).toDouble() / sampleRate.toDouble()
        val sample = (sin(2.0 * PI * frequencyHz * t) * Short.MAX_VALUE * amplitude)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
        out[index] = (sample.toInt() and 0xFF).toByte()
        out[index + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        index += 2
    }
    return out
}
