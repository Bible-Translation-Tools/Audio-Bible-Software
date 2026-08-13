package org.bibletranslationtools.otter.common.data.audio

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * On-disk codec for [AudioMarker], used by the narration `active_verses.json`.
 *
 * WRITES two distinct keys:
 *  - `marker_type`  — which marker this is (book_marker / chapter_marker / verse_marker /
 *                     unknown_marker)
 *  - `content_type` — the [MarkerType] the marker reports (TITLE / CONTENT / METADATA / UNKNOWN)
 *
 * They are separate on purpose. Jackson was configured with
 * `@JsonTypeInfo(As.PROPERTY, property = "type")` while [AudioMarker] also declares its own
 * `type: MarkerType`, and the two collided: Jackson treated the bean's property as the type-id
 * slot, wrote only `"type":"TITLE"`, and could then no longer tell a BookMarker from a
 * VerseMarker on the way back in. Reading Jackson's own output threw
 * `UnrecognizedPropertyException: Unrecognized field "bookSlug"` because every marker fell back to
 * `defaultImpl = VerseMarker`. Book and chapter markers therefore did NOT survive a round trip.
 *
 * READS every shape that has been written to disk:
 *  1. this format, keyed on `marker_type`;
 *  2. Jackson's discriminator-less output, where only `content_type`/`type` is present;
 *  3. the JavaFX-era files, which carry a duplicate `type` key whose first value is a class name
 *     (`"type":"BookMarker"` followed by `"type":"TITLE"`).
 *
 * Shape 3 is why the fallback keys off FIELDS rather than any `type` value: a JSON parser that
 * builds a map keeps the LAST duplicate, so the class name is not recoverable — but `bookSlug`,
 * `chapterNumber` and `start`/`end` are unambiguous. Anything still unrecognised decodes as a
 * [VerseMarker], matching the old `defaultImpl`.
 *
 * `sort` is not written: it is derived from the marker's own fields, and every implementation
 * recomputes it on construction. Older files that contain it are simply ignored.
 */
object AudioMarkerSerializer : KSerializer<AudioMarker> {

    private const val MARKER_TYPE = "marker_type"
    private const val CONTENT_TYPE = "content_type"

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("org.bibletranslationtools.otter.common.data.audio.AudioMarker")

    override fun serialize(encoder: Encoder, value: AudioMarker) {
        val out = encoder as? JsonEncoder
            ?: throw IllegalStateException("AudioMarker can only be written to JSON")
        out.encodeJsonElement(
            buildJsonObject {
                when (value) {
                    is BookMarker -> {
                        put(MARKER_TYPE, JsonPrimitive("book_marker"))
                        put("bookSlug", JsonPrimitive(value.bookSlug))
                    }
                    is ChapterMarker -> {
                        put(MARKER_TYPE, JsonPrimitive("chapter_marker"))
                        put("chapterNumber", JsonPrimitive(value.chapterNumber))
                    }
                    is VerseMarker -> {
                        put(MARKER_TYPE, JsonPrimitive("verse_marker"))
                        put("start", JsonPrimitive(value.start))
                        put("end", JsonPrimitive(value.end))
                    }
                    is UnknownMarker -> {
                        put(MARKER_TYPE, JsonPrimitive("unknown_marker"))
                        put("label", JsonPrimitive(value.label))
                    }
                    else -> throw IllegalArgumentException(
                        "Unknown AudioMarker implementation: ${value::class.simpleName}"
                    )
                }
                put("location", JsonPrimitive(value.location))
                put(CONTENT_TYPE, JsonPrimitive(value.type.name))
            }
        )
    }

    override fun deserialize(decoder: Decoder): AudioMarker {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("AudioMarker can only be read from JSON")
        val obj = input.decodeJsonElement() as? JsonObject
            ?: throw IllegalArgumentException("a marker must be an object")

        val location = obj["location"]?.jsonPrimitive?.int ?: 0

        return when (obj[MARKER_TYPE]?.jsonPrimitive?.contentOrNull) {
            "book_marker" -> BookMarker(obj.str("bookSlug"), location)
            "chapter_marker" -> ChapterMarker(obj["chapterNumber"]!!.jsonPrimitive.int, location)
            "verse_marker" -> VerseMarker(
                obj["start"]!!.jsonPrimitive.int, obj["end"]!!.jsonPrimitive.int, location
            )
            "unknown_marker" -> UnknownMarker(location, obj.str("label"))
            // No marker_type: a file written before this codec. Identify it by its fields.
            else -> when {
                obj.containsKey("bookSlug") -> BookMarker(obj.str("bookSlug"), location)
                obj.containsKey("chapterNumber") ->
                    ChapterMarker(obj["chapterNumber"]!!.jsonPrimitive.int, location)
                obj.containsKey("start") && obj.containsKey("end") -> VerseMarker(
                    obj["start"]!!.jsonPrimitive.int, obj["end"]!!.jsonPrimitive.int, location
                )
                obj.containsKey("label") -> UnknownMarker(location, obj.str("label"))
                // Jackson's defaultImpl was VerseMarker; keep that rather than failing a load.
                else -> VerseMarker(0, 0, location)
            }
        }
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("marker is missing required field `$key`")
}
