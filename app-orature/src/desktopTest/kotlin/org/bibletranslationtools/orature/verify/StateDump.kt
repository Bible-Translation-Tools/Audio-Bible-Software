package org.bibletranslationtools.orature.verify

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What Orature has saved, as JSON, so a check can decide from data rather than from screenshots.
 *
 * Reads only files, never the database or the running app, so it works on any Orature data
 * directory: a verification state, a test's temp directory, your own (read-only), or one pulled
 * off an Android device. For each project (a book folder with `.apps/orature`) it reports:
 * - `mode`, `selectedTakes` and `chunks`: the project's own files, as saved;
 * - per chapter with recordings, `narration`: each line's marker, where it starts, how long its
 *   audio is, and its measured pitch and loudness; and `takes`: each take file, likewise.
 *
 * Pitch is how recordings are told apart without listening: the verify microphone records a
 * 330 Hz tone, or whatever the data directory's `verify-mic-hz` file says, so a verse
 * re-recorded after writing `392` there measures 392 Hz and the rest 330. Real speech has no
 * single pitch; read `rms` (0 is silence) for that.
 *
 * Run: `./gradlew :app-orature:dumpState -PdataDir=<dir> [-Pbook=gen] [-Pchapter=1]`.
 */
object StateDump {

    private const val SAMPLE_RATE = 44_100
    private const val BYTES_PER_FRAME = 2 // 16-bit mono, what Orature records

    fun dump(dataDir: File, book: String? = null, chapter: Int? = null): JsonObject = buildJsonObject {
        put("dataDir", dataDir.absolutePath)
        dataDir.resolve("VERIFY_STATE.txt").takeIf { it.exists() }?.let {
            put("verifyState", it.readLines().first())
        }
        put("projects", buildJsonArray {
            projectDirs(dataDir)
                .filter { book == null || it.name == book }
                .forEach { add(project(it, chapter)) }
        })
    }

    /** A project is a book folder holding `.apps/orature` with something saved in it. */
    fun projectDirs(dataDir: File): List<File> =
        dataDir.walk()
            .onEnter { it.name != "rc" && it.name != "source" }
            .filter { it.isDirectory && it.name == "orature" && it.parentFile.name == ".apps" }
            .map { it.parentFile.parentFile }
            .filter { hasWork(it) }
            .sortedBy { it.path }
            .toList()

    private fun hasWork(project: File): Boolean {
        val apps = project.resolve(".apps/orature")
        return apps.resolve("project_mode.json").exists() ||
            apps.resolve("takes").walk().any { it.isFile && it.length() > 0 && it.name != "active_verses.json" }
    }

    private fun project(dir: File, onlyChapter: Int?): JsonObject = buildJsonObject {
        val apps = dir.resolve(".apps/orature")
        // <data>/<creator>/<source lang_resource>/<target lang_resource>/<book>
        put("book", dir.name)
        put("source", dir.parentFile.parentFile.name)
        put("target", dir.parentFile.name)
        put("path", dir.absolutePath)
        put("mode", apps.resolve("project_mode.json").readJsonOrNull()?.jsonObject?.get("mode") ?: JsonPrimitive(null as String?))
        put("selectedTakes", buildJsonArray {
            apps.resolve("selected.txt").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }
                ?.forEach { add(JsonPrimitive(it)) }
        })
        put("chunks", apps.resolve("chunks.json").readJsonOrNull() ?: JsonPrimitive(null as String?))
        put("chapters", buildJsonArray {
            apps.resolve("takes").listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }
                .mapNotNull { chapterDir ->
                    val number = Regex("c(\\d+)").matchEntire(chapterDir.name)?.groupValues?.get(1)?.toInt()
                    if (onlyChapter != null && number != onlyChapter) null else chapter(chapterDir, number)
                }
                .forEach { add(it) }
        })
    }

    private fun chapter(dir: File, number: Int?): JsonObject? {
        val pcm = dir.resolve("chapter_narration.pcm")
        val verses = dir.resolve("active_verses.json").readJsonOrNull() as? JsonArray
        val takes = dir.walk().filter { it.isFile && it.extension.lowercase() in setOf("wav", "mp3") }.sortedBy { it.name }.toList()
        if ((verses == null || verses.isEmpty()) && takes.isEmpty()) return null
        return buildJsonObject {
            put("chapter", number)
            if (verses != null && verses.isNotEmpty()) put("narration", narration(pcm, verses))
            put("takes", buildJsonArray { takes.forEach { add(take(it)) } })
        }
    }

    /** The narration working audio: one PCM file, cut into lines by `active_verses.json`. */
    private fun narration(pcm: File, verses: JsonArray): JsonObject = buildJsonObject {
        put("audioSeconds", seconds(pcm.length() / BYTES_PER_FRAME))
        put("lines", buildJsonArray {
            verses.forEach { v ->
                val node = v.jsonObject
                val marker = node["marker"]!!.jsonObject
                val sectors = node["sectors"]?.jsonArray.orEmpty().map {
                    it.jsonObject["start"]!!.jsonPrimitive.int..it.jsonObject["end"]!!.jsonPrimitive.int
                }
                val bytes = sectors.sumOf { it.last - it.first + 1 }
                val audio = analyse(pcm, sectors)
                add(buildJsonObject {
                    put("line", label(marker))
                    put("placed", node["placed"]?.jsonPrimitive?.content == "true")
                    put("startSeconds", seconds(marker["location"]?.jsonPrimitive?.int?.toLong() ?: 0))
                    put("audioSeconds", seconds(bytes.toLong() / BYTES_PER_FRAME))
                    put("pieces", sectors.size)
                    put("pitchHz", audio.pitchHz)
                    put("rms", audio.rms)
                })
            }
        })
    }

    private fun label(marker: JsonObject): String = when (marker["marker_type"]?.jsonPrimitive?.content) {
        "book_marker" -> "book"
        "chapter_marker" -> "chapter"
        "verse_marker" -> {
            val start = marker["start"]!!.jsonPrimitive.int
            val end = marker["end"]?.jsonPrimitive?.int ?: start
            if (start == end) "v$start" else "v$start-$end"
        }
        else -> marker["marker_type"]?.jsonPrimitive?.content ?: "?"
    }

    private fun take(file: File): JsonObject = buildJsonObject {
        put("file", file.name)
        put("bytes", file.length())
        if (file.extension.lowercase() == "wav") {
            val wav = Wav.read(file)
            if (wav != null) {
                put("seconds", seconds(wav.dataBytes / (wav.channels * wav.bitsPerSample / 8)))
                val audio = analyse(file, listOf(wav.dataOffset.toInt() until (wav.dataOffset + wav.dataBytes).toInt()))
                put("pitchHz", audio.pitchHz)
                put("rms", audio.rms)
                put("cues", buildJsonArray {
                    wav.cues.forEach { (frame, label) ->
                        add(buildJsonObject {
                            put("label", label)
                            put("seconds", seconds(frame))
                        })
                    }
                })
            }
        }
    }

    private class Audio(val pitchHz: Int?, val rms: Double)

    /**
     * Loudness and pitch of 16-bit mono PCM within [ranges] (byte offsets, inclusive). Pitch is
     * from zero crossings, which is exact for a pure tone and meaningless for speech.
     */
    private fun analyse(file: File, ranges: List<IntRange>): Audio {
        if (!file.exists() || ranges.isEmpty()) return Audio(null, 0.0)
        var frames = 0L
        var sumSquares = 0.0
        var crossings = 0L
        RandomAccessFile(file, "r").use { raf ->
            ranges.forEach { range ->
                val start = range.first.toLong().coerceAtLeast(0)
                val end = minOf(range.last.toLong() + 1, raf.length())
                if (end <= start) return@forEach
                val bytes = ByteArray((end - start).toInt())
                raf.seek(start)
                raf.readFully(bytes)
                var previous = 0
                for (i in 0 until bytes.size - 1 step 2) {
                    val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
                    sumSquares += (sample.toDouble() / Short.MAX_VALUE).let { it * it }
                    if (i > 0 && (previous < 0) != (sample < 0)) crossings++
                    previous = sample
                    frames++
                }
            }
        }
        if (frames == 0L) return Audio(null, 0.0)
        val rms = sqrt(sumSquares / frames)
        val pitch = if (rms < 0.01) null else (crossings / 2.0 / (frames.toDouble() / SAMPLE_RATE)).roundToInt()
        return Audio(pitch, (rms * 1000).roundToInt() / 1000.0)
    }

    private fun seconds(frames: Long): Double = (frames * 100.0 / SAMPLE_RATE).roundToInt() / 100.0

    private fun File.readJsonOrNull(): JsonElement? =
        takeIf { it.exists() && it.length() > 0 }?.let { runCatching { Json.parseToJsonElement(it.readText()) }.getOrNull() }

    private val pretty = Json { prettyPrint = true }
    fun toText(json: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), json)

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: error("Usage: StateDump <dataDir> [book] [chapter]"))
        check(dir.isDirectory) { "No such directory: $dir" }
        println(toText(dump(dir, args.getOrNull(1)?.ifBlank { null }, args.getOrNull(2)?.toIntOrNull())))
    }
}

/** Just enough of a WAV reader for the dump: format, where the samples are, and cue labels. */
private class Wav(
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
    val cues: List<Pair<Long, String>>
) {
    companion object {
        fun read(file: File): Wav? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                fun u32(): Long { val b = ByteArray(4); raf.readFully(b); return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24) }
                fun u16(): Int { val b = ByteArray(2); raf.readFully(b); return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) }
                fun id(): String { val b = ByteArray(4); raf.readFully(b); return String(b, Charsets.US_ASCII) }
                if (id() != "RIFF") return null
                u32(); if (id() != "WAVE") return null
                var channels = 1; var bits = 16; var dataOffset = 0L; var dataBytes = 0L
                val cuePoints = mutableMapOf<Long, Long>()
                val labels = mutableMapOf<Long, String>()
                while (raf.filePointer + 8 <= raf.length()) {
                    val chunk = id(); val size = u32(); val start = raf.filePointer
                    when (chunk) {
                        "fmt " -> { u16(); channels = u16(); u32(); u32(); u16(); bits = u16() }
                        "data" -> { dataOffset = start; dataBytes = minOf(size, raf.length() - start) }
                        "cue " -> repeat(u32().toInt()) { val cueId = u32(); u32(); id(); u32(); u32(); cuePoints[cueId] = u32() }
                        "LIST" -> if (id() == "adtl") {
                            while (raf.filePointer + 8 <= start + size) {
                                val sub = id(); val subSize = u32(); val subStart = raf.filePointer
                                if (sub == "labl") {
                                    val cueId = u32()
                                    val text = ByteArray((subSize - 4).toInt()); raf.readFully(text)
                                    labels[cueId] = String(text, Charsets.UTF_8).trimEnd('\u0000')
                                }
                                raf.seek(subStart + subSize + (subSize and 1))
                            }
                        }
                    }
                    raf.seek(start + size + (size and 1))
                }
                val cues = cuePoints.map { (cueId, frame) -> frame to (labels[cueId] ?: "") }.sortedBy { it.first }
                Wav(channels, bits, dataOffset, dataBytes, cues)
            }
        }.getOrNull()
    }
}

/** One narration line of [book] chapter [chapter] in a dump, such as "v2", for assertions. */
internal fun JsonObject.narrationLine(line: String, book: String = "gen", chapter: Int = 1): JsonObject? =
    this["projects"]?.jsonArray
        ?.firstOrNull { it.jsonObject["book"]?.jsonPrimitive?.content == book }?.jsonObject
        ?.get("chapters")?.jsonArray
        ?.firstOrNull { it.jsonObject["chapter"]?.jsonPrimitive?.content == "$chapter" }?.jsonObject
        ?.get("narration")?.jsonObject?.get("lines")?.jsonArray
        ?.firstOrNull { it.jsonObject["line"]?.jsonPrimitive?.content == line }?.jsonObject

internal fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.content?.toDoubleOrNull()

/** The saved `chunks.json` of [book] in a dump, or null when nothing is saved. */
internal fun JsonObject.savedChunks(book: String = "gen"): JsonElement? =
    this["projects"]?.jsonArray
        ?.firstOrNull { it.jsonObject["book"]?.jsonPrimitive?.content == book }?.jsonObject
        ?.get("chunks")?.takeUnless { it is JsonPrimitive && it.content == "null" }
