package org.bibletranslationtools.otter.common.device

class MockAudioSink : AudioSink {
    var isOpen = false
    var isStarted = false
    var bytesWritten = 0
    override var framePosition: Long = 0
    override var isRunning = false

    override fun open(spec: AudioSpec) { isOpen = true }
   // override fun start() { isStarted = true; isRunning = true }
    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        bytesWritten += size
        framePosition += (size / 2) // Simplified for 16-bit Mono
        return size
    }
   // override fun stop() { isStarted = false; isRunning = false }
    override fun drain() {}
    override fun flush() {}
    override fun close() { isOpen = false }

    override fun start() {
        isStarted = true
        isRunning = true // Ensure this is set
    }

    override fun stop() {
        isStarted = false
        isRunning = false // Ensure this is cleared
    }
}