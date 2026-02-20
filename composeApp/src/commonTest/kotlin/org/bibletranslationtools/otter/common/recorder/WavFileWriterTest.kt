package org.bibletranslationtools.otter.common.recorder

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class WavFileWriterTest {

    @Test
    fun writerPersistsStreamedPcmIntoWavFile() = runTest {
        val tempWav = File.createTempFile("recorder-writer-test", ".wav")
        tempWav.deleteOnExit()

        val stream = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val writer = WavFileWriter(
            oratureAudioFile = OratureAudioFile(tempWav, 1, 44100, 16),
            audioStream = stream,
            append = false,
            onComplete = {},
            scope = this
        )

        writer.listen()
        writer.start()

        // Emit ~1 second of low-frequency sine in small chunks.
        val sampleRate = 44100
        val chunkSamples = 441
        var emitted = 0
        while (emitted < sampleRate) {
            val count = minOf(chunkSamples, sampleRate - emitted)
            stream.emit(sinePcm16le(startIndex = emitted, sampleCount = count, frequencyHz = 3.0, sampleRate = sampleRate))
            emitted += count
        }

        delay(100)
        writer.pause()
        writer.closeAndJoin()

        // WAV should contain header + some payload data.
        assertTrue(tempWav.exists())
        assertTrue(tempWav.length() > 44L, "Expected WAV payload beyond header")
        assertTrue(
            OratureAudioFile(tempWav).totalFrames > 0,
            "Expected finalized WAV header with non-zero frame count"
        )
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
