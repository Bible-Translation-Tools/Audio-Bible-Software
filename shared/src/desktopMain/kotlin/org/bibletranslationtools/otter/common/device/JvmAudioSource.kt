package org.bibletranslationtools.otter.common.device

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

        // Release the line this source already holds BEFORE asking the device for another one.
        //
        // A capture line is exclusive on Windows: with the previous one still open, the `line.open`
        // below fails with "no line ... available"/"line unavailable", and the line we were holding
        // is orphaned — `currentLine` has been overwritten, so nothing can ever close it again. One
        // missed [close] therefore used to disable the microphone for the rest of the process.
        //
        // That is exactly what the record screen hit: its teardown could lose the race with the
        // ViewModel being cleared, so the mic worker never reached `close()`, and re-entering the
        // screen failed to allocate a line. The teardown is fixed at the source (see
        // `AudioRecorder.releaseAsync`); this makes a re-open self-healing rather than fatal, which
        // is what `open()` replacing the line has to mean anyway.
        releaseCurrentLine()

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
        releaseCurrentLine()
    }

    /**
     * Gives back the held line, if any, and forgets it.
     *
     * `stop()` before `close()` because a leaked line can still be running, and every step is
     * wrapped: a stale line that throws on the way out must not stop us releasing the reference or
     * acquiring the next one — the reference is the only thing keeping the device claimed.
     */
    private fun releaseCurrentLine() {
        val line = currentLine ?: return
        currentLine = null
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }
}
