package org.bibletranslationtools.otter.common.data.audio

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the on-disk contract for narration's `active_verses.json`.
 *
 * Two things are being protected. First, that every marker type survives a round trip — which it
 * did NOT under Jackson: `@JsonTypeInfo(property = "type")` collided with AudioMarker's own
 * `type: MarkerType`, so nothing but a VerseMarker could be read back. Second, that files already
 * on disk still load, in all the shapes that have been written over time.
 */
class AudioMarkerSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val markers = ListSerializer(AudioMarkerSerializer)

    @Test
    fun `every marker type survives a round trip`() {
        val original: List<AudioMarker> = listOf(
            BookMarker("gen", 0),
            ChapterMarker(1, 88200),
            VerseMarker(1, 3, 176400),
            UnknownMarker(264600, "something-else")
        )

        val restored = json.decodeFromString(markers, json.encodeToString(markers, original))

        assertEquals(original, restored)
    }

    @Test
    fun `the two type keys are written separately`() {
        val encoded = json.encodeToString(markers, listOf(BookMarker("gen", 0)))

        assertTrue(encoded.contains("\"marker_type\":\"book_marker\""), encoded)
        assertTrue(encoded.contains("\"content_type\":\"TITLE\""), encoded)
        // The single ambiguous `type` key is what made book and chapter markers unreadable.
        assertTrue(!encoded.contains("\"type\":"), encoded)
    }

    @Test
    fun `reads the JavaFX-era shape with a duplicate type key`() {
        // Written before the KMP port: a class-name discriminator, then the MarkerType under the
        // same key. A parser keeping the last duplicate sees only "TITLE", so recovery has to come
        // from the fields.
        val legacy = """
            [{"type":"BookMarker","bookSlug":"gen","location":0,"type":"TITLE","sort":0},
             {"type":"ChapterMarker","chapterNumber":1,"location":88200,"type":"TITLE","sort":1001}]
        """.trimIndent()

        val restored = json.decodeFromString(markers, legacy)

        assertEquals(listOf(BookMarker("gen", 0), ChapterMarker(1, 88200)), restored)
    }

    @Test
    fun `reads the discriminator-less shape Jackson actually wrote`() {
        // Verified against the real mapper: the collision meant no subtype discriminator was
        // emitted at all, only the MarkerType.
        val current = """
            [{"bookSlug":"gen","location":0,"type":"TITLE","sort":0},
             {"chapterNumber":1,"location":88200,"type":"TITLE","sort":1001},
             {"start":1,"end":1,"location":176400,"type":"CONTENT","sort":10001}]
        """.trimIndent()

        val restored = json.decodeFromString(markers, current)

        assertEquals(
            listOf(BookMarker("gen", 0), ChapterMarker(1, 88200), VerseMarker(1, 1, 176400)),
            restored
        )
    }

    @Test
    fun `an unrecognisable marker falls back to a verse marker`() {
        // Jackson declared defaultImpl = VerseMarker; a marker that matches nothing should still
        // not fail the whole load.
        val restored = json.decodeFromString(markers, """[{"location":42}]""")

        assertEquals(listOf(VerseMarker(0, 0, 42)), restored)
    }
}
