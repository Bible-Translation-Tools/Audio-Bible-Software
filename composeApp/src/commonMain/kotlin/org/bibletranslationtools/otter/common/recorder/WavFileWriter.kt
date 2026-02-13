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
package org.bibletranslationtools.otter.common.recorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

class WavFileWriter(
    private val oratureAudioFile: OratureAudioFile,
    private val audioStream: Flow<ByteArray>,
    private val append: Boolean = false,
    private val onComplete: () -> Unit,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(WavFileWriter::class.java)

    private var record = AtomicBoolean(false)
    private val _isWriting = MutableStateFlow(false)
    val isWriting = _isWriting.asStateFlow()

    private var writerJob: Job? = null

    fun start() {
        record.set(true)
        _isWriting.value = true
    }

    fun pause() {
        record.set(false)
        _isWriting.value = false
    }

    /**
     * Starts listening to the audio stream and writing to the file.
     * Call this after initialization.
     */
    fun listen() {
        writerJob = scope.launch(Dispatchers.IO) {
            val writer = oratureAudioFile.writer(append = append, buffered = true)
            try {
                audioStream.collect { byteArray ->
                    if (record.get()) {
                        writer.write(byteArray)
                        writer.flush()
                    }
                }
            } catch (e: Exception) {
                logger.error("Error in WavFileWriter", e)
            } finally {
                writer.close()
                _isWriting.value = false
                onComplete()
            }
        }
    }

    fun close() {
        writerJob?.cancel()
    }
}
