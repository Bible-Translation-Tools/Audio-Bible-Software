package org.bibletranslationtools.otter.integration

import org.bibletranslationtools.otter.common.audio.wav.WavFile
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.ProjectMode
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.domain.narration.AudioFileUtils
import org.bibletranslationtools.otter.common.domain.narration.WriteNarrationVerses
import org.bibletranslationtools.otter.common.domain.project.exporter.ExportResult
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Narration written into a project reaches the backup.
 *
 * The recorder gives a backup its narration by writing the pair into the project and letting the
 * exporter collect it, so the two halves have to agree on where it lives: the writer puts it in the
 * chapter's audio directory, and `copyInProgressNarrationFiles` copies from there into the
 * container's take directory. Nothing else in the recorder would notice if that stopped matching —
 * the export would simply succeed with no narration in it, and the failure would only appear when
 * someone opened the backup in Orature.
 */
class ExportCarriesNarrationTest {

    private var env: IntegrationEnvironment? = null

    @AfterTest
    fun tearDown() {
        env?.close()
        env = null
    }

    private fun judeProject(): Triple<IntegrationEnvironment, Workbook, Chapter> {
        val e = IntegrationEnvironment.create().also { env = it }
        e.import("en_ulb.zip")
        val derived = e.createProject(
            sourceProject = e.sourceBook(BOOK),
            targetLanguage = e.language("en"),
            mode = ProjectMode.DIALECT,
            deriveProjectFromVerses = true
        )
        val workbook = e.workbook(derived)
        // Without an initialized container on disk the exporter reports FAILURE and writes nothing.
        workbook.projectFilesAccessor.initializeResourceContainerInDir()
        workbook.projectFilesAccessor.setProjectMode(ProjectMode.DIALECT)
        val chapter = workbook.target.chapters.toList().blockingGet().single()
        return Triple(e, workbook, chapter)
    }

    private fun take(id: Int, frames: Int): File {
        val file = File.createTempFile("take-$id-", ".wav").apply { deleteOnExit() }
        WavFile(file, 1, 44100, 16).let { wav ->
            wav.writer().use { it.write(ByteArray(frames * 2) { id.toByte() }) }
        }
        return file
    }

    /** Exports [workbook] and returns the entry names in the resulting backup. */
    private fun exportedEntries(e: IntegrationEnvironment, workbook: Workbook): List<String> {
        val out = File(e.directoryProvider.tempDirectory, "export-${System.nanoTime()}")
            .apply { mkdirs() }
        assertEquals(
            ExportResult.SUCCESS,
            e.backupExporter.export(out, workbook, null, null).blockingGet()
        )
        val backup = out.listFiles()?.single { it.extension == "orature" }
        assertTrue(backup != null, "no backup produced in ${out.path}")
        return ZipFile(backup).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
    }

    @Test
    fun `narration written into the project is carried into the backup`() {
        val (e, workbook, chapter) = judeProject()
        WriteNarrationVerses(AudioFileUtils(e.directoryProvider)).execute(
            workbook,
            chapter,
            listOf(
                WriteNarrationVerses.Unit(VerseMarker(1, 1, 0), take(1, 500)),
                WriteNarrationVerses.Unit(VerseMarker(2, 2, 0), take(2, 500))
            )
        )

        val entries = exportedEntries(e, workbook)

        assertTrue(
            entries.any { it.endsWith("chapter_narration.pcm") },
            "the scratch recording must be in the backup; got $entries"
        )
        assertTrue(
            entries.any { it.endsWith("active_verses.json") },
            "the verse map must be in the backup; got $entries"
        )
    }

    @Test
    fun `a backup of a project without narration carries none`() {
        // The other half of the contract: nothing is invented for a project that has no narration,
        // so a compiled chapter take stays the only thing Orature reads.
        val (e, workbook, _) = judeProject()

        val entries = exportedEntries(e, workbook)

        assertTrue(
            entries.none { it.endsWith("chapter_narration.pcm") || it.endsWith("active_verses.json") },
            "unexpected narration in $entries"
        )
    }

    private companion object {
        const val BOOK = "jud"
    }
}
