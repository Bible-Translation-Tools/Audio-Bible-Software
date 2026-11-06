package org.bibletranslationtools.otter.common.audio.flac

import io.nayuki.flac.decode.FlacDecoder
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.device.AudioFileReader
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.bibletranslationtools.otter.common.domain.audio.EncodeAudioToFlac
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlacStreamEncoderTest {
    private val root = createTempDirectory("flac").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** [frames] frames of noise-ish audio, the same for the same [seed]. */
    private fun pcm(frames: Int, channels: Int, bits: Int, seed: Int = 1): ByteArray =
        Random(seed).nextBytes(frames * channels * bits / 8)

    /** A 16-bit mono WAV holding [pcm]. */
    private fun wav(name: String, pcm: ByteArray): File {
        val file = File(root, name)
        OratureAudioFile(file, 1, 44100, 16).writer(append = true).use { it.write(pcm) }
        return file
    }

    private fun encode(pcm: ByteArray, channels: Int, bits: Int, name: String = "out.flac"): Pair<File, FlacEncoding> {
        val flac = File(root, name)
        val encoding = FlacStreamEncoder.encode(BytesReader(pcm, AudioSpec(44100, bits, channels)), 44100, channels, bits, flac)
        return flac to encoding
    }

    /** Every sample in [flac], interleaved, as FLAC decodes it. */
    private fun decode(flac: File): List<Int> = FlacDecoder(flac).use { decoder ->
        while (decoder.readAndHandleMetadataBlock() != null) { }
        val channels = decoder.streamInfo.numChannels
        val block = Array(channels) { IntArray(65536) }
        buildList {
            while (true) {
                val n = decoder.readAudioBlock(block, 0)
                if (n == 0) break
                for (i in 0 until n) for (c in 0 until channels) add(block[c][i])
            }
        }
    }

    /** [pcm]'s samples, signed, as FLAC should give them back. */
    private fun samples(pcm: ByteArray, bits: Int): List<Int> {
        val bytes = bits / 8
        return (0 until pcm.size / bytes).map { s ->
            var value = 0
            for (k in 0 until bytes) value = value or ((pcm[s * bytes + k].toInt() and 0xFF) shl (8 * k))
            if (bits == 8) value - 128 else (value shl (32 - bits)) shr (32 - bits)
        }
    }

    @Test
    fun `every format FLAC holds decodes back to the same samples`() {
        for ((channels, bits) in listOf(1 to 16, 2 to 16, 1 to 24, 2 to 24, 1 to 8, 1 to 32, 3 to 16)) {
            // Not a multiple of the block size, so the last frame is short.
            val pcm = pcm(frames = 10_000, channels = channels, bits = bits)
            val (flac, encoding) = encode(pcm, channels, bits)

            assertEquals(samples(pcm, bits), decode(flac), "$channels ch / $bits bit")
            assertEquals(10_000L, encoding.totalFrames)
            val check = FlacStreamDecoder.check(flac)
            assertTrue(check.lossless, "$channels ch / $bits bit: $check")
            assertEquals(encoding, check.decoded)
        }
    }

    @Test
    fun `the MD5 is FLAC's, which for 16-bit audio is the MD5 of the WAV's samples`() {
        val pcm = pcm(frames = 5_000, channels = 1, bits = 16)

        val (_, encoding) = encode(pcm, 1, 16)

        val md5 = java.security.MessageDigest.getInstance("MD5").digest(pcm).toHex()
        assertEquals(md5, encoding.pcmMd5)
    }

    @Test
    fun `the same audio always encodes to the same bytes`() {
        val pcm = pcm(frames = 20_000, channels = 2, bits = 16)

        val (first, _) = encode(pcm, 2, 16, "first.flac")
        val (second, _) = encode(pcm, 2, 16, "second.flac")

        assertContentEquals(first.readBytes(), second.readBytes())
    }

    @Test
    fun `different audio encodes differently`() {
        val (first, _) = encode(pcm(frames = 5_000, channels = 1, bits = 16, seed = 1), 1, 16, "first.flac")
        val (second, _) = encode(pcm(frames = 5_000, channels = 1, bits = 16, seed = 2), 1, 16, "second.flac")

        assertTrue(!first.readBytes().contentEquals(second.readBytes()))
    }

    @Test
    fun `audio with no frames is an empty FLAC`() {
        val (flac, encoding) = encode(ByteArray(0), 1, 16)

        assertEquals(0L, encoding.totalFrames)
        assertEquals(emptyList(), decode(flac))
        assertTrue(FlacStreamDecoder.check(flac).lossless)
    }

    @Test
    fun `a trailing partial frame is dropped`() {
        val pcm = pcm(frames = 100, channels = 2, bits = 16) + byteArrayOf(1, 2, 3)

        val (_, encoding) = encode(pcm, 2, 16)

        assertEquals(100L, encoding.totalFrames)
    }

    @Test
    fun `audio FLAC can't hold is refused`() {
        assertFailsWith<IllegalArgumentException> { encode(ByteArray(30), 1, 12) }
        assertFailsWith<IllegalArgumentException> { encode(ByteArray(30), 9, 16) }
    }

    @Test
    fun `a recording encodes through OratureAudioFile, its markers left behind`() {
        val pcm = pcm(frames = 9_000, channels = 1, bits = 16)
        val take = wav("take.wav", pcm)
        OratureAudioFile(take).apply { addMarker(VerseMarker(1, 1, 0)); update() }
        val flac = File(root, "take.flac")

        val encoding = runBlocking { EncodeAudioToFlac()(take, flac) }

        assertEquals(9_000L, encoding.totalFrames)
        assertEquals(samples(pcm, 16), decode(flac), "only the audio, not the cue chunks")
    }

    @Test
    fun `a file that isn't the audio it claims is caught`() {
        val (flac, _) = encode(pcm(frames = 5_000, channels = 1, bits = 16), 1, 16)
        // Corrupt the STREAMINFO MD5 (bytes 8..41 hold STREAMINFO; its MD5 is the last 16).
        val bytes = flac.readBytes()
        bytes[41] = (bytes[41].toInt() xor 0xFF).toByte()
        flac.writeBytes(bytes)

        assertTrue(!FlacStreamDecoder.check(flac).lossless)
    }
}

/** Little-endian, interleaved [pcm], handed out in uneven pieces the way a real reader may. */
private class BytesReader(private val pcm: ByteArray, override val spec: AudioSpec) : AudioFileReader {
    private var position = 0
    private var call = 0
    override val framePosition: Int get() = position / spec.bytesPerFrame
    override val totalFrames: Int get() = pcm.size / spec.bytesPerFrame
    override fun hasRemaining() = position < pcm.size
    override fun getPcmBuffer(bytes: ByteArray): Int {
        val limit = if (call++ % 2 == 0) bytes.size else bytes.size / 3 + 1
        val n = minOf(limit, pcm.size - position)
        System.arraycopy(pcm, position, bytes, 0, n)
        position += n
        return n
    }
    override fun seek(frame: Long) {
        position = (frame * spec.bytesPerFrame).toInt()
    }
    override fun open() {}
    override fun release() {}
}
