package org.bibletranslationtools.bttrecorder2.legacymigration

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes WAV files the way the old BTT-Recorder did, for tests: a fixed 44-byte header, the PCM,
 * then its metadata. That is a cue chunk with bare verse numbers as labels, and a LIST/INFO chunk
 * holding the JSON it kept about the take.
 */
internal object LegacyTakeWav {

    /** The PCM [write] puts in a take: a pattern unique to [seed], so joined audio can be checked byte for byte. */
    fun pcm(frames: Int, seed: Int, channels: Int = 1): ByteArray =
        ByteArray(frames * 2 * channels) { i -> ((i * 31 + seed * 17) % 251 - 125).toByte() }

    /**
     * @param declaredAudioBytes what the header claims; null for the truth. An interrupted
     *   recording declares 0 and has no metadata.
     * @param cues bare-numbered cues, label to frame
     * @param metadataJson the JSON the old app wrote into a LIST/INFO/IART chunk
     */
    fun write(
        file: File,
        frames: Int,
        seed: Int,
        declaredAudioBytes: Long? = null,
        cues: List<Pair<String, Int>> = emptyList(),
        metadataJson: String? = null,
        channels: Int = 1,
        sampleRate: Int = 44100
    ): File {
        val audio = pcm(frames, seed, channels)
        val metadata = ByteArrayOutputStream().apply {
            if (cues.isNotEmpty()) {
                write(cueChunk(cues))
                write(labelList(cues))
            }
            metadataJson?.let { write(infoList(it)) }
        }.toByteArray()
        val declared = declaredAudioBytes ?: audio.size.toLong()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + declared + metadata.size).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16)
            putShort(1); putShort(channels.toShort()); putInt(sampleRate)
            putInt(sampleRate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(declared.toInt())
        }
        file.parentFile?.mkdirs()
        file.outputStream().use {
            it.write(header.array())
            it.write(audio)
            it.write(metadata)
        }
        return file
    }

    /** The JSON the old app kept in each take, as WavMetadata.toJSON wrote it. */
    fun metadataJson(mode: String, startVerse: Int, endVerse: Int, markers: Map<String, Int> = emptyMap()): String =
        """{"anthology":"nt","language":"aa","version":"reg","book":"jas","book_number":"59","mode":"$mode",""" +
            """"chapter":"01","startv":"$startVerse","endv":"$endVerse","contributor":"",""" +
            """"markers":{${markers.entries.joinToString(",") { (label, frame) -> "\"$label\":$frame" }}}}"""

    private fun cueChunk(cues: List<Pair<String, Int>>): ByteArray {
        val body = ByteBuffer.allocate(4 + 24 * cues.size).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(cues.size)
        cues.forEachIndexed { id, (_, frame) ->
            body.putInt(id); body.putInt(frame); body.put("data".toByteArray())
            body.putInt(0); body.putInt(0); body.putInt(frame)
        }
        return chunk("cue ", body.array())
    }

    private fun labelList(cues: List<Pair<String, Int>>): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write("adtl".toByteArray())
            cues.forEachIndexed { id, (label, _) ->
                val text = padded(label.toByteArray() + 0.toByte())
                val labl = ByteBuffer.allocate(4 + text.size).order(ByteOrder.LITTLE_ENDIAN).putInt(id).put(text).array()
                write(chunk("labl", labl))
            }
        }.toByteArray()
        return chunk("LIST", body)
    }

    private fun infoList(json: String): ByteArray =
        chunk("LIST", "INFO".toByteArray() + chunk("IART", padded(json.toByteArray() + 0.toByte())))

    private fun chunk(label: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(label.toByteArray()).putInt(body.size).put(body).array()

    /** Pads to a multiple of four bytes, so every chunk stays aligned. */
    private fun padded(bytes: ByteArray): ByteArray = bytes.copyOf((bytes.size + 3) / 4 * 4)
}
