package org.bibletranslationtools.otter.common.device.newaudio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine

class JvmAudioSink(
    private val lineProvider: () -> SourceDataLine?
) : AudioSink {

    private var currentLine: SourceDataLine? = null

    override val isRunning: Boolean
        get() = currentLine?.isRunning ?: false

    override val framePosition: Long
        get() = currentLine?.longFramePosition ?: 0

    override fun open(spec: AudioSpec) {
        val line = lineProvider() ?: throw IllegalStateException("No SourceDataLine available")

        val format = AudioFormat(
            spec.sampleRate.toFloat(),
            spec.bitDepth,
            spec.channels,
            true, // signed
            spec.isBigEndian
        )

        if (!line.isOpen) {
            line.open(format)
        }
        currentLine = line
    }

    override fun start() {
        currentLine?.start()
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        return currentLine?.write(data, offset, size) ?: 0
    }

    override fun stop() {
        currentLine?.stop()
    }

    override fun drain() {
        currentLine?.drain()
    }

    override fun flush() {
        currentLine?.flush()
    }

    override fun close() {
        currentLine?.close()
        currentLine = null
    }
}