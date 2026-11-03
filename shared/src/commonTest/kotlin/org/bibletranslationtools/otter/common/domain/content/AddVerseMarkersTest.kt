package org.bibletranslationtools.otter.common.domain.content

import org.bibletranslationtools.otter.common.data.audio.OratureCueType
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AddVerseMarkersTest {

    @Test
    fun `marks each verse range where its take starts in the compiled chapter`() {
        val takes = listOf(wav(1_000), wav(2_500))
        val compiled = wav(3_500)

        addVerseMarkers(compiled, listOf(1..1, 2..3), takes)

        val markers = OratureAudioFile(compiled).getMarker(OratureCueType.VERSE).sortedBy { it.location }
        assertEquals(listOf(0, 1_000), markers.map { it.location })
        assertEquals(listOf(VerseMarker(1, 1, 0), VerseMarker(2, 3, 1_000)), markers)
    }

    private fun wav(frames: Int): File {
        val file = File.createTempFile("add-verse-markers", ".wav").apply { deleteOnExit() }
        OratureAudioFile(file, 1, 44_100, 16).writer().use { it.write(ByteArray(frames * 2)) }
        return file
    }
}
