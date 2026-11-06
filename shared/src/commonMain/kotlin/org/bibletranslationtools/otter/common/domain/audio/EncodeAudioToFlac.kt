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
package org.bibletranslationtools.otter.common.domain.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.audio.flac.FlacEncoding
import org.bibletranslationtools.otter.common.audio.flac.FlacStreamDecoder
import org.bibletranslationtools.otter.common.audio.flac.FlacStreamEncoder
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Encodes a recording to FLAC and checks the result decodes back to the same audio.
 *
 * The recording is read through [OratureAudioFile], so any format the app records or imports
 * works, not only WAV. Its markers aren't carried over: FLAC here holds audio only.
 */
class EncodeAudioToFlac {
    private val logger = LoggerFactory.getLogger(EncodeAudioToFlac::class.java)

    /**
     * Encodes [source] into [flac], replacing it, and returns what it holds.
     *
     * @throws IllegalArgumentException if FLAC can't hold [source]'s format.
     * @throws IOException if the encoded file doesn't decode back to [source]'s audio; [flac] is
     *   deleted.
     */
    suspend operator fun invoke(source: File, flac: File): FlacEncoding = withContext(Dispatchers.IO) {
        val context = coroutineContext
        val encoding = FlacStreamEncoder.encode(OratureAudioFile(source), flac) { context.ensureActive() }
        val check = FlacStreamDecoder.check(flac)
        if (!check.lossless || check.decoded != encoding) {
            flac.delete()
            throw IOException(
                "${source.name} didn't encode losslessly: encoded $encoding, decoded ${check.decoded}, " +
                    "declared ${check.declared}"
            )
        }
        logger.info(
            "Encoded {} ({} bytes) to {} ({} bytes, {} frames)",
            source.name, source.length(), flac.name, flac.length(), encoding.totalFrames
        )
        encoding
    }
}
