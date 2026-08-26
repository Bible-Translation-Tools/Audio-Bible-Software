/**
 * Copyright (C) 2020-2024 Wycliffe Associates
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
package org.bibletranslationtools.otter.common.audio.mp3

import java.io.File
import java.io.IOException
import java.lang.Integer.max
import java.lang.Integer.min
import org.bibletranslationtools.otter.common.audio.AudioCue
import org.bibletranslationtools.otter.common.audio.AudioFormatStrategy
import org.bibletranslationtools.otter.common.audio.DEFAULT_BITS_PER_SAMPLE
import org.bibletranslationtools.otter.common.audio.DEFAULT_CHANNELS
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.bibletranslationtools.otter.common.device.AudioFileReader
import org.bibletranslationtools.otter.common.device.AudioSpec
import org.yellowcouch.javazoom.RandomAccessDecoder
import java.io.OutputStream
import java.lang.IllegalStateException

// arbitrary size, though setting this too small results in choppy playback
private const val MP3_BUFFER_SIZE = 24576

class MP3FileReader(
    val file: File,
    start: Int? = null,
    end: Int? = null,
    override val spec: AudioSpec = AudioSpec(sampleRate = DEFAULT_SAMPLE_RATE, bitDepth = DEFAULT_BITS_PER_SAMPLE, channels = DEFAULT_CHANNELS)
) : AudioFormatStrategy, AudioFileReader {

    private var decoder: RandomAccessDecoder? = RandomAccessDecoder(file.absolutePath)

    val start = start ?: 0
    val decodedMax = decoder?.sampleCount
    val end = if (decodedMax != null && end != null) {
        min(decodedMax, end)
    } else decodedMax ?: 0
    private var pos = min(max(0, this.start), this.end)

    override val sampleRate: Int = DEFAULT_SAMPLE_RATE
    override val channels: Int = DEFAULT_CHANNELS

    override val framePosition: Int
        get() = pos - start

    override val totalFrames: Int
        get() = end - start
    override val bitsPerSample = DEFAULT_BITS_PER_SAMPLE

    override val metadata = Mp3Metadata(
        mp3File = file,
        cueFile = File(file.parent, "${file.nameWithoutExtension}.cue")
    )

    private val buff = ShortArray(MP3_BUFFER_SIZE * 2)

    override fun addCue(location: Int, label: String) {
        metadata.addCue(location, label)
    }

    override fun getCues(): List<AudioCue> {
        return metadata.getCues()
    }

    override fun update() {
        decoder?.stop()
        metadata.write()
    }

    override fun reader(start: Int?, end: Int?): AudioFileReader {
        return MP3FileReader(file, start, end)
    }

    override fun writer(append: Boolean, buffered: Boolean): OutputStream {
        TODO("Not yet implemented")
    }

    private fun getPCMData(outBuff: ByteArray, pos: Int) {
        // Only the part of the scratch buffer this call will actually read. The loop below reads
        // `buff` at even indices up to outBuff.size - 2, so outBuff.size shorts is all that matters;
        // fillBuffers used to copy all 49152 of them on every call regardless. The player asks for
        // 1024 frames at a time (AudioProcessor.inputBufferSize), i.e. 2048 bytes, so ~96% of that
        // copy was thrown away — roughly two million wasted short-copies per second of audio, each
        // one a bounds-checked read plus a ring-buffer mask. Invisible on a desktop, not on an
        // Android 7 tablet decoding MP3 in pure Java while Compose redraws the waveform.
        //
        // The decode itself is untouched: the `seek` below still asks for the same lookahead, so the
        // decoder fills at exactly the same rate and this stays a pure removal of copying.
        val needed = min(buff.size, outBuff.size)
        fillBuffers(pos, buff, needed)
        var j = 0
        for (i in 0 until needed step 2) {
            val leftShort = buff[i].toInt()
            outBuff[j++] = (leftShort and 0xff).toByte()
            outBuff[j++] = (leftShort ushr 0x08 and 0xff).toByte()
        }
    }

    /** Copies [count] shorts of decoded audio starting at frame [pos] into [leftRight]. */
    private fun fillBuffers(pos: Int, leftRight: ShortArray, count: Int) {
        decoder?.let { _decoder ->
            val sourceAudio = _decoder.audioShorts
            var sourceIdx = 0
            try {
                sourceIdx = _decoder.seek(pos, leftRight.size / 2) and RandomAccessDecoder.BUFFER_LAST
            } catch (e: IOException) {
                e.printStackTrace()
            }
            for (i in 0 until count) {
                leftRight[i] = sourceAudio[sourceIdx++]
                sourceIdx = sourceIdx and RandomAccessDecoder.BUFFER_LAST
            }
        }
    }

    override fun hasRemaining(): Boolean {
        return decoder?.let { _decoder ->
            pos < min(_decoder.sampleCount, end)
        } ?: throw IllegalStateException("hasRemaining called before opening file")
    }

    override fun getPcmBuffer(bytes: ByteArray): Int {
        val remainingFrames = (end - pos)
        getPCMData(bytes, pos)
        pos += bytes.size / 2
        // remaining frames is multiplied by 2 for bitrate (16 bit)
        return bytes.size.coerceAtMost(remainingFrames * 2)
    }

    override fun seek(frame: Long) {
        // seek API should not be aware of audio outside of start and end;
        // so that a selected section can be treated as its own "track"
        val mappedSample = frame.toInt() + start
        pos = max(start, min(mappedSample, end))
    }

    override fun open() {
        decoder?.let { release() }
        decoder = RandomAccessDecoder(file.absolutePath)

        pos = min(max(0, this.start), this.end)
    }

    override fun release() {
        decoder?.stop()
        decoder = null
        pos = 0
        System.gc()
    }

    override fun close() {
        release()
    }
}
