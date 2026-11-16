package org.bibletranslationtools.otter.common.domain.content

import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.ReplayRelay
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Single
import org.bibletranslationtools.otter.common.audio.wav.IWaveFileCreator
import org.bibletranslationtools.otter.common.data.primitives.CheckingStatus
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeCheckingState
import org.bibletranslationtools.otter.common.data.workbook.TakeHolder
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.utils.computeFileChecksum
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterTranslationBuilderTest {

    private fun tempFile(bytes: ByteArray) =
        File.createTempFile("chapter", ".wav").apply { writeBytes(bytes); deleteOnExit() }

    /**
     * Compiling a chapter whose chunk audio has not changed returns the chapter take it already
     * has. It must not add that take to the chapter a second time.
     */
    @Test
    fun `compiling an unchanged chapter does not add its take again`() {
        val compiled = tempFile(byteArrayOf(1, 2, 3))
        val existing = Take(
            name = "chapter_t1.wav", file = tempFile(byteArrayOf(1, 2, 3)), number = 1,
            format = MimeType.WAV, createdTimestamp = LocalDate.now(),
            checkingState = BehaviorRelay.createDefault(
                TakeCheckingState(CheckingStatus.VERSE, computeFileChecksum(compiled))
            )
        )
        val chapterAudio = AssociatedAudio(ReplayRelay.create(), BehaviorRelay.createDefault(TakeHolder(existing)))
        chapterAudio.insertTake(existing)

        val chunkTake = Take(
            name = "chunk_t1.wav", file = tempFile(byteArrayOf(9)), number = 1,
            format = MimeType.WAV, createdTimestamp = LocalDate.now()
        )
        val chunk = mockk<Chunk>(relaxed = true)
        every { chunk.hasSelectedAudio() } returns true
        every { chunk.audio } returns AssociatedAudio(ReplayRelay.create(), BehaviorRelay.createDefault(TakeHolder(chunkTake)))
        val chapter = mockk<Chapter>(relaxed = true)
        every { chapter.chunks } returns Single.just(listOf(chunk))
        every { chapter.audio } returns chapterAudio

        val concatenate = mockk<ConcatenateAudio>()
        every { concatenate.execute(any(), includeMarkers = false) } answers { Single.just(tempFile(byteArrayOf(1, 2, 3))) }
        val builder = ChapterTranslationBuilder(concatenate, TakeCreator(mockk<IWaveFileCreator>(relaxed = true)))

        val result = builder.getOrCompile(mockk<Workbook>(relaxed = true), chapter).blockingGet()

        assertEquals(existing, result)
        assertEquals(1, chapterAudio.getAllTakes().size, "the chapter's take was added a second time")
    }
}
