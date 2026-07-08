package org.bibletranslationtools.otter.common.device.newaudio

class MockAudioSource : AudioSource {
    var isOpen = false
    var isStarted = false

    override fun open(spec: AudioSpec) { isOpen = true }
    override fun start() { isStarted = true }
    override fun stop() { isStarted = false }
    override fun close() { isOpen = false }

    override fun read(data: ByteArray, offset: Int, size: Int): Int {
        if (!isStarted) return 0
        // Fill with dummy data to simulate sound
        for (i in 0 until size) { data[i] = (i % 128).toByte() }
        return size
    }
}