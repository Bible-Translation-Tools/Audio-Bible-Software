package org.bibletranslationtools.otter.common.audio.wav

import org.bibletranslationtools.otter.common.audio.DEFAULT_BITS_PER_SAMPLE
import org.bibletranslationtools.otter.common.audio.DEFAULT_CHANNELS
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import java.io.File

/**
 * Cross-platform empty wav creator used by TakeCreator.
 */
class WaveFileCreator : IWaveFileCreator {
    override fun createEmpty(path: File) {
        OratureAudioFile(
            file = path,
            channels = DEFAULT_CHANNELS,
            sampleRate = DEFAULT_SAMPLE_RATE,
            bitsPerSample = DEFAULT_BITS_PER_SAMPLE
        )
    }
}
