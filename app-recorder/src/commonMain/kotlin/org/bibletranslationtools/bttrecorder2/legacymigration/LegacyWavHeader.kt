package org.bibletranslationtools.bttrecorder2.legacymigration

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Checks, and repairs, the header of a take the old BTT-Recorder wrote.
 *
 * The old app wrote a fixed 44-byte header, then the PCM, then its metadata (LIST and cue chunks).
 * The header's length fields were placeholders while recording and were only set once a take was
 * finished. A take whose recording was interrupted therefore declares no audio, or the wrong amount,
 * while its PCM runs to the end of the file. `OratureAudioFile` trusts the declared length, so it
 * can't read such a take until the header is right.
 *
 * This is the one place the migration touches WAV bytes directly, and only for that layout. The
 * original file is never changed: [repairedCopy] writes a new file.
 */
internal object LegacyWavHeader {

    const val HEADER_SIZE = 44

    sealed interface State {
        /** The header can be trusted (or isn't the old app's layout, so `OratureAudioFile` decides). */
        data object Readable : State

        /** The old app's layout with a wrong length; [audioBytes] is how much PCM the file really holds. */
        data class NeedsRepair(val audioBytes: Long) : State

        data class Unrepairable(val reason: String) : State
    }

    fun inspect(file: File): State {
        val length = file.length()
        if (length < HEADER_SIZE) return State.Unrepairable("shorter than a WAV header")
        val header = readHeader(file)
        if (!header.isOldAppLayout()) return State.Readable

        val blockAlign = header.getShort(22) * (header.getShort(34) / 8)
        if (blockAlign <= 0) return State.Unrepairable("its header declares no audio format")
        val available = length - HEADER_SIZE
        val declared = header.getInt(40).toLong() and 0xFFFFFFFFL
        val declaredFits = declared in 1..available && declared % blockAlign == 0L &&
            (declared == available || chunkLabelAt(file, HEADER_SIZE + declared) in METADATA_CHUNKS)
        if (declaredFits) return State.Readable

        val audioBytes = available - available % blockAlign
        return if (audioBytes > 0) State.NeedsRepair(audioBytes) else State.Unrepairable("it holds no audio")
    }

    /** Writes [source] to [destination] with its header's lengths set to [audioBytes] of PCM, and nothing after it. */
    fun repairedCopy(source: File, audioBytes: Long, destination: File) {
        val header = readHeader(source)
        header.putInt(4, (HEADER_SIZE - 8 + audioBytes).toInt())
        header.putInt(40, audioBytes.toInt())
        destination.outputStream().buffered().use { out ->
            out.write(header.array())
            RandomAccessFile(source, "r").use { input ->
                input.seek(HEADER_SIZE.toLong())
                var remaining = audioBytes
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    // RandomAccessFile rather than InputStream.readNBytes, which Android only has from API 33.
    private fun readHeader(file: File): ByteBuffer {
        val bytes = ByteArray(HEADER_SIZE)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    /** RIFF/WAVE, a 16-byte PCM fmt chunk, then the data chunk at byte 36: what the old app wrote. */
    private fun ByteBuffer.isOldAppLayout(): Boolean =
        text(0) == "RIFF" && text(8) == "WAVE" && text(12) == "fmt " && getInt(16) == 16 &&
            getShort(20).toInt() == PCM_FORMAT && text(36) == "data"

    private fun ByteBuffer.text(at: Int) = String(ByteArray(4) { get(at + it) }, Charsets.US_ASCII)

    private fun chunkLabelAt(file: File, offset: Long): String? =
        RandomAccessFile(file, "r").use { raf ->
            if (offset + 4 > raf.length()) return null
            raf.seek(offset)
            ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
        }

    private const val PCM_FORMAT = 1
    private val METADATA_CHUNKS = setOf("LIST", "cue ")
}
