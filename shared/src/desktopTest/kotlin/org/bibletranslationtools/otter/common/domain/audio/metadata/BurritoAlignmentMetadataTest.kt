package org.bibletranslationtools.otter.common.domain.audio.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bibletranslationtools.otter.common.data.audio.BookMarker
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BurritoAlignmentMetadataTest {
    private val root = createTempDirectory("burrito-alignment").toFile()
    private val audio = File(root, "en_ulb_mrk_c01_meta_t1.wav").apply { writeText("audio") }
    private val timing = File(root, "en_ulb_mrk_c01_meta_t1.json")
    private val markers = listOf(BookMarker("mrk", 0), ChapterMarker(1, 0), VerseMarker(1, 1, 44100), VerseMarker(2, 2, 88200))

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun documents() = Json.parseToJsonElement(timing.readText()).jsonObject
        .getValue("groups").jsonArray.single().jsonObject
        .getValue("documents").jsonArray.map { it.jsonObject }

    @Test
    fun `a new timing file is SB-ALIGN 0_4 with USFM Scripture references`() {
        BurritoAlignmentMetadata(timing, audio).write(markers, "mrk", 1, 44100 * 3)

        val document = Json.parseToJsonElement(timing.readText()).jsonObject
        assertEquals("0.4", document.getValue("version").jsonPrimitive.content)
        assertEquals(
            listOf("vtt-timecode", "usfm-scripture-reference"),
            documents().map { it.getValue("scheme").jsonPrimitive.content },
        )
    }

    @Test
    fun `in a burrito the audio is named by its path, and still reads back by its file name`() {
        BurritoAlignmentMetadata(timing, audio, docid = "media/${audio.name}").write(markers, "mrk", 1, 44100 * 3)

        assertEquals("media/${audio.name}", documents().first().getValue("docid").jsonPrimitive.content)
        val read = BurritoAlignmentMetadata(timing, audio).parseTimings()
        assertEquals(markers.map { it.label }.sorted(), read.getMarkers().map { it.label }.sorted())
    }
}
