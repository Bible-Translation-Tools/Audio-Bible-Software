package org.bibletranslationtools.otter.common.device.newaudio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine

class JvmAudioSource(
    private val lineProvider: () -> TargetDataLine?
) : AudioSource {

    private var currentLine: TargetDataLine? = null

    override fun open(spec: AudioSpec) {
        val line = try {
            lineProvider()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to acquire TargetDataLine from provider", e)
        } ?: throw IllegalStateException("No TargetDataLine available")

        val format = AudioFormat(
            spec.sampleRate.toFloat(),
            spec.bitDepth,
            spec.channels,
            true,
            spec.isBigEndian
        )

        if (!line.isOpen) {
            try {
                line.open(format)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Unable to open TargetDataLine with format $format",
                    e
                )
            }
        }
        currentLine = line
    }

    override fun start() {
        currentLine?.start()
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        return currentLine?.read(buffer, offset, size) ?: 0
    }

    override fun stop() {
        currentLine?.stop()
        currentLine?.flush()
    }

    override fun close() {
        currentLine?.close()
        currentLine = null
    }
}
