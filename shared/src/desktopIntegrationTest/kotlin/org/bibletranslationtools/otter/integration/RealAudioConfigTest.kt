package org.bibletranslationtools.otter.integration

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.device.AudioConfig
import org.bibletranslationtools.otter.common.device.AudioDevice
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.otter.common.device.DefaultAudioProcessor
import org.bibletranslationtools.otter.common.device.JvmAudioDeviceSelector
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.device.JvmAudioHardwareProvider
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Whether [AudioConfig] actually reaches the hardware — at the shipped settings and at the ones it is
 * expected to be raised to.
 *
 * This is the test the config abstraction exists for. Before it, the format was re-created as a bare
 * `AudioSpec()` at a dozen call sites and the output buffer was a constructor argument nothing passed,
 * so "record at 48k" or "use a 20 ms buffer" were not one-line changes — they were twelve-line changes
 * with no way to tell whether you had found them all. Here a config is built, handed to the real
 * hardware bridge, and the resulting device is measured against what was asked for.
 *
 * It runs the format sweep rather than just the default on purpose: 48 kHz / 24-bit / stereo is the
 * documented destination, and the claim that it works end to end should fail loudly if it stops being
 * true, not be rediscovered when someone tries it.
 *
 * In the integration tier because it opens the real default output device. Skips when there is none.
 */
class RealAudioConfigTest {

    @Test
    fun theConfiguredBufferIsWhatTheHardwareGets() {
        if (!hasOutputDevice()) return

        // Every size across the useful range, including the shipped default. The mixer's own choice —
        // measured at 500ms — is what these replace.
        val sizes = listOf(50, 80, 100, 200)
        val results = sizes.map { millis ->
            val sink = JvmAudioHardwareProvider(AudioConfig(outputBufferMillis = millis))
                .createSink(defaultOutputDevice())
            try {
                sink.open(AudioSpec())
                val actual = (sink as org.bibletranslationtools.otter.common.device.JvmAudioSink)
                    .bufferFrames * 1_000L / AudioSpec().sampleRate
                millis to actual
            } finally {
                sink.close()
            }
        }
        println("[AUDIO] configured buffer -> device: ${results.joinToString { "${it.first}ms=>${it.second}ms" }}")

        results.forEach { (asked, got) ->
            assertTrue(
                abs(got - asked) <= 2,
                "asked for ${asked}ms of buffer and the sink was built with ${got}ms. The config is not " +
                    "reaching the hardware, which is the whole point of it (all: $results)."
            )
        }
    }

    @Test
    fun playbackWorksEndToEndAtEveryConfiguredFormat() {
        if (!hasOutputDevice()) return

        val formats = listOf(
            AudioSpec(sampleRate = 44_100, bitDepth = 16, channels = 1), // shipped
            AudioSpec(sampleRate = 48_000, bitDepth = 16, channels = 1),
            AudioSpec(sampleRate = 48_000, bitDepth = 24, channels = 2)  // the documented destination
        )

        val report = mutableListOf<String>()
        var anyFailed = false

        for (spec in formats) {
            val config = AudioConfig(spec = spec)
            val result = runCatching { measureSteadyPlayback(config) }
            val label = "${spec.sampleRate}/${spec.bitDepth}/${spec.channels}ch"
            if (result.isFailure) {
                anyFailed = true
                report += "$label FAILED (${result.exceptionOrNull()?.message})"
                continue
            }
            val errorMillis = result.getOrThrow()
            if (abs(errorMillis) >= STEADY_TOLERANCE_MILLIS) anyFailed = true
            report += "$label error=${errorMillis}ms"
        }

        println("[AUDIO] playback across configured formats: ${report.joinToString("; ")}")
        assertTrue(
            !anyFailed,
            "a configured format did not play back correctly end to end: $report. Position error is the " +
                "measure because it is what breaks first when a format is mis-plumbed — a wrong " +
                "bytes-per-frame anywhere makes the position advance at the wrong rate rather than throw."
        )
    }

    /**
     * Playback follows the FILE's format, not the configured one.
     *
     * [AudioConfig.spec] says what this app records at. What it plays at is whatever the take happens to
     * be, because a take is read back through its own reader — and getting that wrong is not subtle: a
     * 48k file opened as 44.1k plays ~9% slow, and every marker and waveform frame lands in the wrong
     * place. So the config here is deliberately set to something the files are NOT, which is what makes a
     * pass mean something.
     *
     * Round-tripped through real WAV files on disk rather than a synthetic reader, because the header is
     * half of what is being checked.
     */
    @Test
    fun playbackOpensTheDeviceAtTheFilesFormatNotTheConfiguredOne() {
        if (!hasOutputDevice()) return
        val config = AudioConfig(spec = AudioSpec(sampleRate = 44_100, bitDepth = 16, channels = 1))

        val formats = listOf(
            AudioSpec(sampleRate = 48_000, bitDepth = 16, channels = 1),
            AudioSpec(sampleRate = 48_000, bitDepth = 24, channels = 2),
            AudioSpec(sampleRate = 44_100, bitDepth = 16, channels = 2)
        )
        val report = mutableListOf<String>()

        for (fileSpec in formats) {
            val file = writeSilentWav(fileSpec, millis = 400)
            try {
                val reader = OratureAudioFile(file).reader()
                val opened = mutableListOf<AudioSpec>()
                val sink = RecordingSink(
                    JvmAudioHardwareProvider(config).createSink(defaultOutputDevice()),
                    opened
                )
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                try {
                    val factory =
                        AudioPlayerConnectionFactory.createForScope(sink, DefaultAudioProcessor(), scope)
                    val connection = AudioPlayerConnection(1, factory, scope, Dispatchers.Default)
                    runBlocking {
                        connection.load(reader)
                        delay(400)
                        connection.pause()
                        delay(100)
                    }
                } finally {
                    scope.cancel()
                    sink.close()
                }

                val label = "${fileSpec.sampleRate}/${fileSpec.bitDepth}/${fileSpec.channels}ch"
                report += "$label read=${describe(reader.spec)} opened=${opened.map(::describe)}"

                assertEquals(fileSpec, reader.spec, "the reader did not report the format the file was written in ($label)")
                assertTrue(opened.isNotEmpty(), "the device was never opened for $label")
                assertEquals(
                    fileSpec,
                    opened.last(),
                    "the device was opened at ${describe(opened.last())} for a $label file. Playback must " +
                        "follow the file, not AudioConfig.spec (${describe(config.spec)}) — a mismatched " +
                        "rate plays at the wrong speed and puts every marker frame in the wrong place."
                )
            } finally {
                file.delete()
            }
        }
        println("[AUDIO] playback follows the file: ${report.joinToString("; ")}")
    }

    /** Wraps a sink to record the specs it is opened with. */
    private class RecordingSink(
        private val inner: org.bibletranslationtools.otter.common.device.AudioSink,
        private val opened: MutableList<AudioSpec>
    ) : org.bibletranslationtools.otter.common.device.AudioSink by inner {
        override fun open(spec: AudioSpec) {
            opened += spec
            inner.open(spec)
        }
    }

    private fun describe(spec: AudioSpec) = "${spec.sampleRate}/${spec.bitDepth}/${spec.channels}ch"

    private fun writeSilentWav(spec: AudioSpec, millis: Int): java.io.File {
        val file = java.io.File.createTempFile("fmt_", ".wav")
        val audio = OratureAudioFile(file, spec.channels, spec.sampleRate, spec.bitDepth)
        val frames = spec.sampleRate * millis / 1_000
        // append: the header was just written by the constructor above, so samples go AFTER it.
        audio.writer(append = true, buffered = true).use { writer ->
            writer.write(ByteArray(frames * spec.bytesPerFrame))
            writer.flush()
        }
        return file
    }

    /**
     * The buffer floor is a real constraint, not advice, so the config refuses to hold a value below it.
     *
     * The failure it prevents is quiet: a buffer smaller than two write chunks cannot keep the line fed
     * between writes, so it underruns continuously and the symptom is stuttering audio rather than an
     * error. Measured at 20 ms, playback barely started at all.
     */
    @Test
    fun aBufferBelowTheWriteChunkFloorIsRejected() {
        val at44k = AudioConfig.minimumOutputBufferMillis(AudioSpec())
        val at48k = AudioConfig.minimumOutputBufferMillis(AudioSpec(sampleRate = 48_000))
        println("[AUDIO] buffer floor: 44.1k=${at44k}ms 48k=${at48k}ms (default ${AudioConfig.DEFAULT_OUTPUT_BUFFER_MILLIS}ms)")

        assertFailsWith<IllegalArgumentException> { AudioConfig(outputBufferMillis = 20) }
        // And the shipped default clears the floor at both rates, so raising `spec` cannot silently
        // invalidate it.
        assertTrue(AudioConfig.DEFAULT_OUTPUT_BUFFER_MILLIS >= at44k)
        assertTrue(AudioConfig.DEFAULT_OUTPUT_BUFFER_MILLIS >= at48k)
        assertEquals(
            AudioConfig.DEFAULT_OUTPUT_BUFFER_MILLIS,
            AudioConfig().outputBufferMillis,
            "the default config must be constructible"
        )
    }

    /**
     * Discovery and playback have to agree about the format, or a device is offered that cannot then be
     * opened. They agree by construction now — both read [AudioConfig.spec] — and this is the check that
     * the platform actually honours it.
     */
    @Test
    fun deviceDiscoveryIsFilteredByTheConfiguredFormat() {
        if (!hasOutputDevice()) return
        val selector = JvmAudioDeviceSelector()

        val shipped = selector.getOutputDevices(AudioSpec())
        val target = selector.getOutputDevices(AudioSpec(sampleRate = 48_000, bitDepth = 24, channels = 2))
        val absurd = selector.getOutputDevices(AudioSpec(sampleRate = 1, bitDepth = 8, channels = 7))

        println(
            "[AUDIO] devices offered: 44.1/16/1=${shipped.size} 48/24/2=${target.size} absurd=${absurd.size}"
        )
        assertTrue(shipped.isNotEmpty(), "the shipped format must find at least the default device")
        assertTrue(
            absurd.isEmpty(),
            "discovery must actually filter on the spec, or the list it offers is not a list of devices " +
                "that will open — it returned ${absurd.size} devices for a format nothing supports"
        )
    }

    /** Plays for a second at [config]'s format and returns the position error in milliseconds. */
    private fun measureSteadyPlayback(config: AudioConfig): Long {
        val sink = JvmAudioHardwareProvider(config).createSink(defaultOutputDevice())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            sink.open(config.spec)
            val factory = AudioPlayerConnectionFactory.createForScope(sink, DefaultAudioProcessor(), scope)
            val connection = AudioPlayerConnection(1, factory, scope, Dispatchers.Default)
            return runBlocking {
                connection.load(
                    SilentReader(totalFrames = config.spec.sampleRate * 10, spec = config.spec)
                )
                delay(300)
                connection.play()
                awaitPositionMoving(connection, config.spec)

                val from = connection.getLocationInFrames().toLong()
                val startNanos = System.nanoTime()
                delay(1_000)
                val advanced = connection.getLocationInFrames().toLong() - from
                val elapsedNanos = System.nanoTime() - startNanos
                connection.pause()
                delay(100)

                val expected = elapsedNanos * config.spec.sampleRate / 1_000_000_000L
                (advanced - expected) * 1_000 / config.spec.sampleRate
            }
        } finally {
            scope.cancel()
            sink.close()
        }
    }

    private suspend fun awaitPositionMoving(connection: AudioPlayerConnection, spec: AudioSpec) {
        val from = connection.getLocationInFrames().toLong()
        val threshold = spec.sampleRate / 100 // 10ms
        val deadline = System.nanoTime() + START_TIMEOUT_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (connection.getLocationInFrames().toLong() - from >= threshold) return
            delay(2)
        }
        throw IllegalStateException("playback never started within ${START_TIMEOUT_MILLIS}ms at $spec")
    }

    private fun hasOutputDevice(): Boolean {
        val supported = AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, null))
        if (!supported) println("[AUDIO] no SourceDataLine on this machine; skipping")
        return supported
    }

    /** The provider falls back to the system default when the id matches nothing, which is what we want. */
    private fun defaultOutputDevice() =
        AudioDevice(id = "", name = "default", type = AudioDevice.DeviceType.OUTPUT)

    private companion object {
        const val STEADY_TOLERANCE_MILLIS = 100
        const val START_TIMEOUT_MILLIS = 5_000L
    }
}
