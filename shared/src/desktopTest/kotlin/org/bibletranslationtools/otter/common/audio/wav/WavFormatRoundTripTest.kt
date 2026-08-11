package org.bibletranslationtools.otter.common.audio.wav

import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A WAV written at a given format must read back at that format.
 *
 * Sounds like it could not fail. It failed in two independent ways, and both were invisible because the
 * app only ever wrote one format:
 *
 *  - [WavFile]'s `channels` / `sampleRate` / `bitsPerSample` arguments were accepted and then discarded —
 *    the header was always built at the defaults. Every file was labelled 44100/16/mono whatever the
 *    caller asked for, so an insert recorded at a 48k take's format produced a clip whose header said
 *    44.1k. Nothing downstream can detect that; it plays at the wrong speed.
 *  - The `fmt ` chunk's SIZE field was written as `bitsPerSample`. For 16-bit audio that is 16, which is
 *    also the correct size of a PCM fmt chunk, so it was right by coincidence for every file in
 *    existence. At 24-bit it declared a 24-byte chunk, and the parser skipped past the `data` label and
 *    rejected the file it had just written.
 *
 * The matrix is the point: one format cannot catch either bug, and both were latent until the format
 * became something the caller could choose.
 */
class WavFormatRoundTripTest {

    @Test
    fun everySupportedFormatSurvivesAWriteAndARead() {
        val formats = listOf(
            Format(channels = 1, sampleRate = 44_100, bits = 16), // the shipped default
            Format(channels = 2, sampleRate = 44_100, bits = 16),
            Format(channels = 1, sampleRate = 48_000, bits = 16),
            Format(channels = 1, sampleRate = 44_100, bits = 24),
            Format(channels = 1, sampleRate = 48_000, bits = 24),
            Format(channels = 2, sampleRate = 48_000, bits = 24)  // the documented destination
        )

        for (format in formats) {
            val file = File.createTempFile("roundtrip_", ".wav")
            try {
                val frames = format.sampleRate / 10 // 100ms
                OratureAudioFile(file, format.channels, format.sampleRate, format.bits)
                    .writer(append = true, buffered = true)
                    .use { it.write(ByteArray(frames * format.channels * format.bits / 8)) }

                val read = OratureAudioFile(file)
                assertEquals(format.channels, read.channels, "channels round-tripped wrong for $format")
                assertEquals(format.sampleRate, read.sampleRate, "sample rate round-tripped wrong for $format")
                assertEquals(format.bits, read.bitsPerSample, "bit depth round-tripped wrong for $format")
                assertEquals(
                    frames,
                    read.totalFrames,
                    "frame count round-tripped wrong for $format — the header's frame size disagrees with " +
                        "the samples, which is how audio ends up playing at the wrong speed"
                )
            } finally {
                file.delete()
            }
        }
    }

    private data class Format(val channels: Int, val sampleRate: Int, val bits: Int) {
        override fun toString() = "$channels ch / $sampleRate Hz / $bits bit"
    }
}
