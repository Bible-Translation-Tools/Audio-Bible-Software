package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the JSON the old BTT-Recorder wrote into each take, as its `WavMetadata` did: after the
 * audio the header declares, in a LIST chunk of type INFO, under IART.
 *
 * Read-only, and null whenever there is nothing to read: no metadata, a header whose length can't
 * be trusted (a recording that never finished has none), or JSON that doesn't parse.
 */
internal object LegacyWavMetadata {

    /** The old app's fields, as written; which mode a [mode] slug means is the anthology's to say. */
    data class Fields(
        val mode: String?,
        val startVerse: Int?,
        val endVerse: Int?,
        val markers: List<LegacyMarker>
    )

    fun read(file: File): Fields? = runCatching { readOrThrow(file) }.getOrNull()

    private fun readOrThrow(file: File): Fields? = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        if (length < HEADER_SIZE) return null
        val header = ByteArray(HEADER_SIZE).also { raf.readFully(it) }
        val audioLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(AUDIO_LENGTH_AT).toLong() and 0xFFFFFFFFL
        val metadataStart = HEADER_SIZE + audioLength
        if (audioLength <= 0 || metadataStart >= length) return null
        val metadataLength = (length - metadataStart).coerceAtMost(MAX_METADATA.toLong()).toInt()
        val metadata = ByteArray(metadataLength)
        raf.seek(metadataStart)
        raf.readFully(metadata)
        iartJson(metadata)?.let(::parse)
    }

    /**
     * The IART text in the first LIST/INFO chunk that has one. By offsets rather than a buffer's
     * position: ByteBuffer.position(int) and limit(int) resolve to overloads Android only has from
     * API 34.
     */
    private fun iartJson(bytes: ByteArray): String? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var at = 0
        while (at + 8 <= bytes.size) {
            val label = label(bytes, at)
            val size = buffer.getInt(at + 4)
            val body = at + 8
            if (size < 0 || body + size > bytes.size) return null
            if (label == "LIST" && size >= 4 && label(bytes, body) == "INFO") {
                var sub = body + 4
                while (sub + 8 <= body + size) {
                    val subSize = buffer.getInt(sub + 4)
                    if (subSize < 0 || sub + 8 + subSize > body + size) break
                    if (label(bytes, sub) == "IART") {
                        return bytes.copyOfRange(sub + 8, sub + 8 + subSize).decodeToString().trimEnd('\u0000')
                    }
                    sub += 8 + subSize
                }
            }
            at = body + size
        }
        return null
    }

    private fun parse(json: String): Fields? {
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val markers = (obj["markers"] as? JsonObject).orEmpty().mapNotNull { (label, position) ->
            runCatching { position.jsonPrimitive.intOrNull }.getOrNull()?.let { LegacyMarker(label, it.toLong()) }
        }
        return Fields(
            mode = text("mode")?.lowercase(),
            startVerse = text("startv")?.trim()?.toIntOrNull(),
            endVerse = text("endv")?.trim()?.toIntOrNull(),
            markers = markers
        )
    }

    private fun label(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)

    private const val HEADER_SIZE = 44
    private const val AUDIO_LENGTH_AT = 40
    /** Far more than any take's metadata; a bound against reading a corrupt file's tail whole. */
    private const val MAX_METADATA = 1 shl 20
}
