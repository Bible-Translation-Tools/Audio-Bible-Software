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
import kotlinx.coroutines.launch
import org.bibletranslationtools.otter.common.audio.DEFAULT_SAMPLE_RATE
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.collections.FloatRingBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

const val RENDERER_BUFFER_SIZE = 1024

class ActiveRecordingRenderer(
    private val stream: Flow<ByteArray>,
    private var recordingStatus: Flow<Boolean>,
    width: Int,
    secondsOnScreen: Int,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(ActiveRecordingRenderer::class.java)

    private var isActive = AtomicBoolean(false)

    // double the width as for each pixel there will be a min and max value
    val floatBuffer = FloatRingBuffer(width * 2)
    private val pcmCompressor = PCMCompressor(floatBuffer, framesToCompress(width, secondsOnScreen))
    val bb = ByteBuffer.allocate(RENDERER_BUFFER_SIZE)

    private var renderingJob: Job? = null
    private var statusJob: Job? = null

    init {
        bb.order(ByteOrder.LITTLE_ENDIAN)

        statusJob = scope.launch {
            recordingStatus.collect {
                isActive.set(it)
            }
        }

        renderingJob = scope.launch(Dispatchers.IO) {
            try {
                stream.collect { byteArray ->
                    bb.put(byteArray)
                    bb.position(0)
                    while (bb.hasRemaining()) {
                        // Handle potential buffer underflow if packet size isn't aligned with Short (2 bytes)
                        if (bb.remaining() >= 2) {
                            val short = bb.short
                            if (isActive.get()) {
                                pcmCompressor.add(short.toFloat())
                            }
                        } else {
                            // Should not happen with standard audio buffers, but good to be safe
                            break 
                        }
                    }
                    bb.clear()
                }
            } catch (e: Exception) {
                logger.error("Error in active renderer stream", e)
            }
        }
    }

    private fun framesToCompress(width: Int, secondsOnScreen: Int): Int {
        // TODO: get samplerate from wav file, don't assume 44.1khz
        return (DEFAULT_SAMPLE_RATE * secondsOnScreen) / width
    }

    /** Sets a new status listener and removes the old one */
    fun setRecordingStatusFlow(value: Flow<Boolean>) {
        statusJob?.cancel()
        recordingStatus = value
        statusJob = scope.launch {
            recordingStatus.collect { isActive.set(it) }
        }
    }

    /** Clears rendered data frombuffer */
    fun clearData() {
        floatBuffer.clear()
        Arrays.fill(pcmCompressor.accumulator, 0f)
    }

    fun close() {
        renderingJob?.cancel()
        statusJob?.cancel()
    }
}
