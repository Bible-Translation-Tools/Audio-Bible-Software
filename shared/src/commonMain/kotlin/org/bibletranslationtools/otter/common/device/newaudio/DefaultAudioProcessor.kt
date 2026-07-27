package org.bibletranslationtools.otter.common.device.newaudio

import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.WaveformSimilarityBasedOverlapAdd
import be.tarsos.dsp.io.TarsosDSPAudioFloatConverter
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking

/**
 * Shared DSP processor that works on JVM and Android.
 */
class DefaultAudioProcessor : AudioProcessor {
    private val mutex = Mutex()
    private var _playbackRate: Double = 1.0

    private lateinit var tarsosFormat: TarsosDSPAudioFormat
    private lateinit var converter: TarsosDSPAudioFloatConverter
    private lateinit var wsola: WaveformSimilarityBasedOverlapAdd
    private lateinit var audioEvent: AudioEvent

    override val playbackRate: Double get() = _playbackRate

    override fun configure(spec: AudioSpec) {
        runBlocking {
            mutex.withLock {
                tarsosFormat = TarsosDSPAudioFormat(
                    spec.sampleRate.toFloat(),
                    spec.bitDepth,
                    spec.channels,
                    true, // signed
                    spec.isBigEndian
                )
                converter = TarsosDSPAudioFloatConverter.getConverter(tarsosFormat)
                audioEvent = AudioEvent(tarsosFormat)
                updateWsolaInternal(_playbackRate)
            }
        }
    }

    override fun setPlaybackRate(rate: Double) {
        _playbackRate = rate
        runBlocking {
            mutex.withLock {
                if (::tarsosFormat.isInitialized) {
                    updateWsolaInternal(rate)
                }
            }
        }
    }

    private fun updateWsolaInternal(rate: Double) {
        val params = WaveformSimilarityBasedOverlapAdd.Parameters.speechDefaults(
            rate,
            tarsosFormat.sampleRate.toDouble()
        )
        wsola = WaveformSimilarityBasedOverlapAdd(params)
    }

    override fun process(input: ByteArray): ByteArray = runBlocking {
        mutex.withLock {
            // JVM and Android both handle ByteArrays the same way here
            val floatBuffer = FloatArray(input.size / (tarsosFormat.sampleSizeInBits / 8))
            converter.toFloatArray(input, floatBuffer)

            audioEvent.floatBuffer = floatBuffer
            wsola.process(audioEvent)

            audioEvent.byteBuffer.copyOf()
        }
    }

    override val overlap: Int get() = if (::wsola.isInitialized) wsola.overlap else 0
    override val inputBufferSize: Int get() = if (::wsola.isInitialized) wsola.inputBufferSize else 1024
}