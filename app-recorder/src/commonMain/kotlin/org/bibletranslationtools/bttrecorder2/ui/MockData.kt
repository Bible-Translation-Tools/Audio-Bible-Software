package org.bibletranslationtools.bttrecorder2.ui

import io.reactivex.Completable
import io.reactivex.Single
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.ReplayRelay
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.MimeType
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.workbook.AssociatedAudio
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Chunk
import org.bibletranslationtools.otter.common.data.workbook.Take
import org.bibletranslationtools.otter.common.data.workbook.TakeHolder
import org.bibletranslationtools.otter.common.data.workbook.TextItem
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
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

    fun createMockTake(number: Int, name: String = "Take $number") = Take(
        name = name,
        file = File("mock/path/$name.wav"),
        number = number,
        format = MimeType.WAV!!,
        createdTimestamp = LocalDate.now()
    )

    fun createMockAssociatedAudio(takesList: List<Take> = emptyList()): AssociatedAudio {
        val replay = ReplayRelay.create<Take>()
        takesList.forEach { replay.accept(it) }
        val behavior = BehaviorRelay.createDefault(TakeHolder(takesList.firstOrNull()))
        return AssociatedAudio(replay, behavior)
    }

    fun createMockChunk(sort: Int, label: String, hasAudio: Boolean = false) = Chunk(
        sort = sort,
        label = label,
        audio = createMockAssociatedAudio(if (hasAudio) listOf(createMockTake(1)) else emptyList()),
        resources = emptyList(),
        textItem = TextItem("Mock Text", MimeType.USFM!!),
        start = sort,
        end = sort,
        draftNumber = 1,
        contentType = ContentType.TEXT
    )

    fun createMockChapter(sort: Int, title: String, label: String, chunkCount: Int = 1) = Chapter(
        sort = sort,
        title = title,
        label = label,
        audio = createMockAssociatedAudio(),
        resources = emptyList(),
        subtreeResources = emptyList(),
        lazychunks = lazy {
            val relay = BehaviorRelay.createDefault((1..chunkCount).map {
                createMockChunk(
                    it,
                    it.toString()
                )
            })
            relay
        },
        chunkCount = Single.just(chunkCount),
        addChunk = { Completable.complete() },
        reset = { Completable.complete() }
    )

    val mockWorkbooks = listOf(
        createMockWorkbook(1, "gen", "Genesis", englishLanguage),
        createMockWorkbook(2, "exo", "Exodus", englishLanguage),
        createMockWorkbook(3, "lev", "Leviticus", spanishLanguage)
    )
}
