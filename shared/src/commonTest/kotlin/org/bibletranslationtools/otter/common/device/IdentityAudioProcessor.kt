package org.bibletranslationtools.otter.common.device

class IdentityAudioProcessor : AudioProcessor {
    override var playbackRate: Double = 1.0
        private set
    private var spec: AudioSpec = AudioSpec()

    override fun configure(spec: AudioSpec) {
        this.spec = spec
    }

    override fun setPlaybackRate(rate: Double) {
        playbackRate = rate
    }

    override fun process(input: ByteArray): ByteArray {
        // Just returns exactly what it got
        return input
    }

    override val overlap: Int = 0
    override val inputBufferSize: Int = 1024
}