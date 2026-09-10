package org.bibletranslationtools.bttrecorder2.imports

import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.importer.ImportCallbackParameter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The callback's answers have to match what the importer does when given none, because supplying a
 * callback is what enables the importer's interactive paths in the first place.
 *
 * The chapter answer is the one that bites. `OngoingProjectImporter` reads the chosen chapters off
 * the reply and aborts the whole import when there are none — and it only asks when the project
 * already exists, so an answer that drops the chapters lets a file import once and then fail on
 * every later attempt, reporting nothing.
 */
class AcceptAllImportCallbackTest {

    @Test
    fun `every offered chapter is accepted`() {
        val callback = AcceptAllImportCallback()

        val options = callback
            .onRequestUserInput(ImportCallbackParameter(listOf(1, 2, 3), "Jude"))
            .blockingGet()

        assertEquals(listOf(1, 2, 3), options.chapters, "dropping these aborts the import")
        assertEquals(true, options.confirmed)
    }

    @Test
    fun `an empty offer stays empty rather than becoming null`() {
        // Nothing to choose from is not the same as "the user chose nothing"; only the latter
        // aborts, so the distinction has to survive.
        val options = AcceptAllImportCallback()
            .onRequestUserInput(ImportCallbackParameter(emptyList(), "Jude"))
            .blockingGet()

        assertEquals(emptyList(), options.chapters)
    }

    @Test
    fun `overwrite confirmation is answered yes`() {
        // What the importer assumes when it has no callback to ask.
        val options = AcceptAllImportCallback().onRequestUserInput().blockingGet()

        assertEquals(true, options.confirmed)
    }

    @Test
    fun `the imported project is reported, and its absence is not`() {
        // The only reason this callback exists.
        val reported = mutableListOf<String>()
        val callback = AcceptAllImportCallback { reported += it.slug }

        callback.onNotifySuccess("English", "Jude", descriptor("jud"))
        assertEquals(listOf("jud"), reported)

        callback.onNotifySuccess("English", "Jude", null)
        assertEquals(listOf("jud"), reported, "no descriptor means nothing to report")
    }

    private fun descriptor(slug: String): WorkbookDescriptor {
        val collection = Collection(sort = 1, slug = slug, labelKey = "project", titleKey = slug, resourceContainer = null)
        return WorkbookDescriptor(
            id = 1,
            sourceCollection = collection,
            targetCollection = collection,
            mode = ProjectMode.DIALECT,
            progress = Single.just(0.0)
        )
    }

    @Test
    fun `progress and errors are ignored without throwing`() {
        val callback = AcceptAllImportCallback()

        callback.onNotifyProgress("importingSource", "en_ulb", 25.0)
        callback.onNotifyProgress(null, null, null)
        callback.onError("/some/path.zip")
    }
}
