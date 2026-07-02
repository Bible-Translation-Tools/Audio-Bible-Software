package org.bibletranslationtools.bttrecorder2.ui.playback

import org.bibletranslationtools.otter.common.device.newaudio.AudioFileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a compressed waveform of the full audio duration into exactly [width]
 * min/max columns.
 *
 * Unlike AudioReaderDrawable (which compresses at an INTEGER frames-per-pixel and
 * therefore both mis-scales and overflows its ring buffer when frames/pixel is
 * small — as it is for a whole-file minimap on a wide window), this bins each
 * frame with the exact ratio `frameIndex * width / totalFrames`. Frame f lands on
 * pixel `f * width / totalFrames`, so the playhead (progress * width) and a
 * click-to-seek (x / width) map to the drawn audio with no drift and no gap.
 */
class MinimapWaveformRenderer(
    private val reader: AudioFileReader,
    private val width: Int
) {
    fun render(): FloatArray {
        val w = width.coerceAtLeast(1)
        val out = FloatArray(w * 2)
        val totalFrames = reader.totalFrames
        if (totalFrames <= 0) return out

        val mins = FloatArray(w) { Float.MAX_VALUE }
        val maxs = FloatArray(w) { -Float.MAX_VALUE }

        // 16-bit samples (matches AudioReaderDrawable's assumption). channels shorts
        // per frame; the first channel is representative for an overview.
        val channels = reader.spec.channels.coerceAtLeast(1)
        val bytesPerFrame = reader.spec.bytesPerFrame.coerceAtLeast(2)
        val buf = ByteArray(65536)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

        reader.seek(0)
        var frameIndex = 0L
        var retry = 0
        while (frameIndex < totalFrames && reader.hasRemaining()) {
            val bytesRead = reader.getPcmBuffer(buf)
            if (bytesRead <= 0) {
                if (++retry >= 10) break else continue
            }
            retry = 0
            bb.position(0)
            val framesRead = bytesRead / bytesPerFrame
            for (f in 0 until framesRead) {
                val sample = bb.short.toFloat()
                for (c in 1 until channels) bb.short   // skip remaining channels
                val bucket = (frameIndex * w / totalFrames).toInt().coerceIn(0, w - 1)
                if (sample < mins[bucket]) mins[bucket] = sample
                if (sample > maxs[bucket]) maxs[bucket] = sample
                frameIndex++
                if (frameIndex >= totalFrames) break
            }
        }

        for (x in 0 until w) {
            out[x * 2] = if (mins[x] == Float.MAX_VALUE) 0f else mins[x]
            out[x * 2 + 1] = if (maxs[x] == -Float.MAX_VALUE) 0f else maxs[x]
        }
        return out
    }

    fun close() {
        reader.release()
    }
}
