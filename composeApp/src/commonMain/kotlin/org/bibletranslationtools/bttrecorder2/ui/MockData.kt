package org.bibletranslationtools.bttrecorder2.ui

import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.primitives.*
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

    fun createMockWorkbook(id: Int, slug: String, label: String, language: Language) = WorkbookDescriptor(
        id = id,
        sourceCollection = createMockCollection(slug, label, language),
        targetCollection = createMockCollection(slug, label, language),
        mode = ProjectMode.TRANSLATION,
        progress = Single.just(0.5),
        hasSourceAudio = true
    )

    val mockWorkbooks = listOf(
        createMockWorkbook(1, "gen", "Genesis", englishLanguage),
        createMockWorkbook(2, "exo", "Exodus", englishLanguage),
        createMockWorkbook(3, "lev", "Leviticus", spanishLanguage)
    )
}
