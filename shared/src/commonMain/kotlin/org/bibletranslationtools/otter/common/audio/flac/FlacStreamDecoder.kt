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

import io.nayuki.flac.decode.FlacDecoder
import java.io.File
import java.security.MessageDigest

/**
 * A FLAC file as decoded: [decoded] is what its audio actually is, [declared] what its STREAMINFO
 * says it is. They match for a file that decodes back to the audio it was encoded from.
 */
data class FlacCheck(val decoded: FlacEncoding, val declared: FlacEncoding) {
    val lossless: Boolean get() = decoded == declared
}

/** Decodes FLAC a block at a time, so memory stays the same however long the recording. */
object FlacStreamDecoder {
    /** FLAC's largest block. */
    private const val MAX_BLOCK_SIZE = 65536

    /**
     * Decodes all of [flac] and compares the audio with what its STREAMINFO declares.
     *
     * @throws java.io.IOException if [flac] isn't a FLAC file it can decode.
     */
    fun check(flac: File): FlacCheck = FlacDecoder(flac).use { decoder ->
        while (decoder.readAndHandleMetadataBlock() != null) {
            // Every metadata block has to be read before the audio.
        }
        val info = decoder.streamInfo
        val bytesPerSample = info.sampleDepth / 8
        val samples = Array(info.numChannels) { IntArray(MAX_BLOCK_SIZE) }
        val bytes = ByteArray(MAX_BLOCK_SIZE * info.numChannels * bytesPerSample)
        val md5 = MessageDigest.getInstance("MD5")
        var frames = 0L
        while (true) {
            val n = decoder.readAudioBlock(samples, 0)
            if (n == 0) break
            var at = 0
            for (i in 0 until n) {
                for (channel in samples) {
                    val value = channel[i]
                    for (k in 0 until bytesPerSample) {
                        bytes[at++] = (value ushr (k * 8)).toByte()
                    }
                }
            }
            md5.update(bytes, 0, at)
            frames += n
        }
        FlacCheck(
            decoded = FlacEncoding(frames, md5.digest().toHex()),
            declared = FlacEncoding(info.numSamples, info.md5Hash.toHex())
        )
    }
}
