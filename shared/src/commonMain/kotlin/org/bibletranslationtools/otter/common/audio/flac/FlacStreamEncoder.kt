/*
 * Copyright (C) 2020-2026 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.audio.flac

import io.nayuki.flac.common.StreamInfo
import io.nayuki.flac.encode.BitOutputStream
import io.nayuki.flac.encode.RandomAccessFileOutputStream
import io.nayuki.flac.encode.StreamFrameEncoder
import io.nayuki.flac.encode.SubframeEncoder
import org.bibletranslationtools.otter.common.audio.AudioFile
import org.bibletranslationtools.otter.common.device.AudioFileReader
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * What a FLAC file holds: [totalFrames] frames of audio whose PCM has the MD5 [pcmMd5] (lowercase
 * hex), computed the way FLAC's STREAMINFO defines it.
 *
 * The MD5 identifies the audio whichever encoder produced the file; the file's own bytes, and so
 * its LFS oid, are only the same for the same encoder and settings.
 */
data class FlacEncoding(val totalFrames: Long, val pcmMd5: String)

/**
 * Encodes audio to FLAC a block at a time, so memory stays the same however long the recording.
 *
 * The same audio always encodes to the same bytes, on desktop and on Android: the vendored encoder
 * avoids platform-dependent math (see `libs/flac/README.md`), and the settings here are fixed.
 * Changing [BLOCK_SIZE] or the search options changes every file it writes.
 */
object FlacStreamEncoder {
    /** Samples per frame: FLAC's usual block size. */
    const val BLOCK_SIZE = 4096

    private val searchOptions = SubframeEncoder.SearchOptions.SUBSET_BEST

    /**
     * Encodes [audio] into [flac], replacing it. [onBlock] is called after each block with the
     * number of frames encoded so far, and may throw to stop.
     *
     * @throws IllegalArgumentException if FLAC can't hold [audio]'s format: it needs 8, 16, 24 or
     *   32 bits per sample, 1 to 8 channels, and a sample rate below 2^20 Hz.
     */
    fun encode(audio: AudioFile, flac: File, onBlock: (framesDone: Long) -> Unit = {}): FlacEncoding =
        audio.reader().use { reader ->
            reader.open()
            encode(reader, audio.sampleRate, audio.channels, audio.bitsPerSample, flac, onBlock)
        }

    /** Encodes the little-endian, interleaved PCM [reader] gives, as [encode] does for a file. */
    fun encode(
        reader: AudioFileReader,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        flac: File,
        onBlock: (framesDone: Long) -> Unit = {}
    ): FlacEncoding {
        require(bitsPerSample in setOf(8, 16, 24, 32)) { "FLAC can't encode $bitsPerSample-bit audio" }
        require(channels in 1..8) { "FLAC can't encode $channels channels" }
        require(sampleRate in 1 until (1 shl 20)) { "FLAC can't encode a $sampleRate Hz sample rate" }

        val bytesPerSample = bitsPerSample / 8
        val frameBytes = bytesPerSample * channels
        val pcm = PcmBlockReader(reader, BLOCK_SIZE * frameBytes)
        val md5 = MessageDigest.getInstance("MD5")
        val md5Bytes = ByteArray(BLOCK_SIZE * frameBytes)
        val info = StreamInfo().apply {
            this.sampleRate = sampleRate
            this.numChannels = channels
            this.sampleDepth = bitsPerSample
            minBlockSize = BLOCK_SIZE
            maxBlockSize = BLOCK_SIZE
            minFrameSize = 0
            maxFrameSize = 0
        }

        var position = 0L
        RandomAccessFile(flac, "rw").use { file ->
            file.setLength(0)
            val out = BitOutputStream(BufferedOutputStream(RandomAccessFileOutputStream(file)))
            out.writeInt(32, FLAC_MAGIC)
            // A placeholder: the sample count, frame sizes and MD5 are only known at the end.
            info.write(true, out)

            while (true) {
                val bytes = pcm.readBlock()
                val frames = bytes / frameBytes
                if (frames == 0) break
                val samples = Array(channels) { LongArray(frames) }
                var at = 0
                for (i in 0 until frames) {
                    for (c in 0 until channels) {
                        val value = sample(pcm.block, at, bytesPerSample)
                        samples[c][i] = value.toLong()
                        // FLAC hashes signed little-endian samples; only 8-bit differs from the WAV bytes.
                        for (k in 0 until bytesPerSample) {
                            md5Bytes[at + k] = (value ushr (k * 8)).toByte()
                        }
                        at += bytesPerSample
                    }
                }
                md5.update(md5Bytes, 0, frames * frameBytes)

                val frameSize = StreamFrameEncoder.encode(
                    position.toInt(), samples, bitsPerSample, sampleRate, searchOptions, out
                )
                if (info.minFrameSize == 0 || frameSize < info.minFrameSize) info.minFrameSize = frameSize
                if (frameSize > info.maxFrameSize) info.maxFrameSize = frameSize
                position += frames
                onBlock(position)
            }
            out.flush()

            info.numSamples = position
            info.md5Hash = md5.digest()
            // STREAMINFO sits right after the magic number, at a fixed offset.
            file.seek(4)
            info.write(true, out)
            out.flush()
        }
        return FlacEncoding(position, info.md5Hash.toHex())
    }

    /** One sample from [bytes] at [offset], as FLAC sees it: signed, so 8-bit WAV's unsigned bytes are re-centred. */
    private fun sample(bytes: ByteArray, offset: Int, bytesPerSample: Int): Int {
        var value = 0
        for (k in 0 until bytesPerSample) {
            value = value or ((bytes[offset + k].toInt() and 0xFF) shl (k * 8))
        }
        return if (bytesPerSample == 1) {
            value - 128
        } else {
            val shift = 32 - bytesPerSample * 8
            (value shl shift) shr shift
        }
    }

    private const val FLAC_MAGIC = 0x664C6143 // "fLaC"
}

/**
 * Fills [block] from [reader], whose [AudioFileReader.getPcmBuffer] may return fewer bytes than
 * asked for. A trailing partial frame is the caller's to drop.
 */
private class PcmBlockReader(private val reader: AudioFileReader, size: Int) {
    val block = ByteArray(size)
    private val chunk = ByteArray(size)
    private var chunkLength = 0
    private var chunkPosition = 0

    /** Fills [block] as far as the audio goes and returns how many bytes it holds. */
    fun readBlock(): Int {
        var filled = 0
        while (filled < block.size) {
            if (chunkPosition == chunkLength) {
                if (!reader.hasRemaining()) break
                chunkLength = reader.getPcmBuffer(chunk)
                chunkPosition = 0
                if (chunkLength <= 0) break
            }
            val n = minOf(block.size - filled, chunkLength - chunkPosition)
            System.arraycopy(chunk, chunkPosition, block, filled, n)
            chunkPosition += n
            filled += n
        }
        return filled
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
