package org.bibletranslationtools.bttrecorder2.ui

import io.reactivex.Single
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.data.workbook.fixture.WorkbookBuilder
import java.io.File
import java.time.LocalDate

object MockData {
    val englishLanguage = Language(
        slug = "eng",
        name = "English",
        anglicizedName = "English",
        direction = "ltr",
        isGateway = true,
        region = "US"
    )

    val spanishLanguage = Language(
        slug = "spa",
        name = "Español",
        anglicizedName = "Spanish",
        direction = "ltr",
        isGateway = true,
        region = "ES"
    )

    fun createMockCollection(slug: String, label: String, language: Language) = Collection(
        sort = 1,
        slug = slug,
        labelKey = label,
        titleKey = label,
        resourceContainer = ResourceMetadata(
            conformsTo = "rc0.2",
            creator = "WA",
            description = "Mock",
            format = "audio/wav",
            identifier = slug,
            issued = LocalDate.now(),
            language = language,
            modified = LocalDate.now(),
            publisher = "WA",
            subject = "Bible",
            type = ContainerType.Bundle,
            title = label,
            version = "1",
            license = "CC BY-SA 4.0",
            path = File("")
        )
    )

    fun createMockWorkbook(id: Int, slug: String, label: String, language: Language) =
        WorkbookDescriptor(
            id = id,
            sourceCollection = createMockCollection(slug, label, language),
            targetCollection = createMockCollection(slug, label, language),
            mode = ProjectMode.TRANSLATION,
            progress = Single.just(0.5),
            hasSourceAudio = true
        )

    /**
     * The preview project's workbook: three chapters of three verses. Built with [WorkbookBuilder],
     * the builder the tests use, so its chapters, chunks and takes are made the way the app makes
     * them. Chapter 1's first verse has three takes, the first selected.
     */
    private val mockProject: Workbook by lazy {
        WorkbookBuilder().build {
            target {
                (1..3).forEach { sort ->
                    chapter(sort) {
                        verse(1) {
                            if (sort == 1) {
                                take("take1.wav", selected = true)
                                take("take2.wav")
                                take("take3.wav")
                            }
                        }
                        verse(2)
                        verse(3)
                    }
                }
            }
        }
    }

    /** Chapter [sort] (1 to 3) of the preview project. */
    fun createMockChapter(sort: Int): Chapter =
        runBlocking { mockProject.target.chapters().first { it.sort == sort } }

    /** A verse of chapter 1: verse 1, which has takes, when [hasAudio]; otherwise verse 2. */
    fun createMockChunk(hasAudio: Boolean = false): Chunk =
        runBlocking { createMockChapter(1).chunks()[if (hasAudio) 0 else 1] }

    /** The takes of chapter 1's first verse, in number order. */
    fun createMockTakes(): List<Take> =
        createMockChunk(hasAudio = true).audio.getAllTakes().sortedBy { it.number }

    val mockWorkbooks = listOf(
        createMockWorkbook(1, "gen", "Genesis", englishLanguage),
        createMockWorkbook(2, "exo", "Exodus", englishLanguage),
        createMockWorkbook(3, "lev", "Leviticus", spanishLanguage)
    )
}
