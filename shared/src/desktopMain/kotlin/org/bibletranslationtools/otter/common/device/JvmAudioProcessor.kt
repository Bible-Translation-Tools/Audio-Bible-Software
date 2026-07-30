package org.bibletranslationtools.otter.common.device

import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.WaveformSimilarityBasedOverlapAdd
import be.tarsos.dsp.io.TarsosDSPAudioFloatConverter
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking

class JvmAudioProcessor : AudioProcessor {

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

                // Initialize WSOLA with speech-optimized defaults
                val params = WaveformSimilarityBasedOverlapAdd.Parameters.speechDefaults(
                    _playbackRate,
                    tarsosFormat.sampleRate.toDouble()
                )
                wsola = WaveformSimilarityBasedOverlapAdd(params)
            }
        }
    }

    override fun setPlaybackRate(rate: Double) {
        _playbackRate = rate
        // We re-initialize WSOLA with the new rate.
        // WSOLA doesn't support hot-swapping the rate on the same instance effectively.
        runBlocking {
            mutex.withLock {
                if (::tarsosFormat.isInitialized) {
                    val params = WaveformSimilarityBasedOverlapAdd.Parameters.speechDefaults(
                        rate,
                        tarsosFormat.sampleRate.toDouble()
                    )
                    wsola = WaveformSimilarityBasedOverlapAdd(params)
                }
            }
        }
    }

    override fun process(input: ByteArray): ByteArray = runBlocking {
        mutex.withLock {
            // 1. Convert PCM Bytes to Floats [-1.0, 1.0]
            val floatBuffer = FloatArray(input.size / (tarsosFormat.sampleSizeInBits / 8))
            converter.toFloatArray(input, floatBuffer)

            // 2. Feed into the Tarsos Event
            audioEvent.floatBuffer = floatBuffer

            // 3. Process with WSOLA (Time Stretching)
            wsola.process(audioEvent)

            // 4. Return the resulting bytes from the event
            // Tarsos stores the processed result back in the event's byteBuffer
            audioEvent.byteBuffer.copyOf()
        }
    }

    override val overlap: Int
        get() = if (::wsola.isInitialized) wsola.overlap else 0

    override val inputBufferSize: Int
        get() = if (::wsola.isInitialized) wsola.inputBufferSize else 1024
}