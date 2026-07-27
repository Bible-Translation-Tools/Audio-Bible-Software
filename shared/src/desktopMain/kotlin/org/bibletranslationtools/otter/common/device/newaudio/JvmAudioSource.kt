package org.bibletranslationtools.otter.common.device.newaudio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

class JvmAudioSource(
    private val lineProvider: () -> TargetDataLine?
) : AudioSource {

    private var currentLine: TargetDataLine? = null

    override fun open(spec: AudioSpec) {
        val format = AudioFormat(
            spec.sampleRate.toFloat(),
            spec.bitDepth,
            spec.channels,
            true,
            spec.isBigEndian
        )

        val line = try {
            lineProvider()
        } catch (e: Exception) {
            null
        } ?: fallbackLine(format)
            ?: throw IllegalStateException("No TargetDataLine available for format $format")

        try {
            if (line.isOpen) {
                line.close()
            }
            line.open(format)
        } catch (e: Exception) {
            throw IllegalStateException("Unable to open TargetDataLine with format $format", e)
        }

        currentLine = line
    }

    private fun fallbackLine(format: AudioFormat): TargetDataLine? {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val default = runCatching {
            AudioSystem.getLine(info) as TargetDataLine
        }.getOrNull()
        if (default != null) {
            return default
        }

        return AudioSystem.getMixerInfo()
            .asSequence()
            .mapNotNull { mixerInfo ->
                runCatching {
                    val mixer = AudioSystem.getMixer(mixerInfo)
                    if (!mixer.isLineSupported(info)) {
                        null
                    } else {
                        mixer.getLine(info) as TargetDataLine
                    }
                }.getOrNull()
            }
            .firstOrNull()
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
