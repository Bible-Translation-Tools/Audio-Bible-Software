package org.bibletranslationtools.bttrecorder2.migration

import org.bibletranslationtools.otter.common.audio.wav.WavFile
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import org.bibletranslationtools.otter.common.domain.audio.WriteTakeMarkers
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.WriteDerivedManifest
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Staging legacy projects as a container project import can read.
 *
 * What import does with the result is covered against a real database by `ImportStagedDirectoryTest`
 * in `:shared`. These cover what only staging decides: the filenames, since a take's filename is the
 * only thing binding it to a unit; the numbering, since merging several legacy projects into one is
 * where take numbers collide; and which take each unit's `selected.txt` line names.
 */
class StageLegacyBackupTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "stage-test-${System.nanoTime()}")
    private val legacyRoot = File(root, "legacy").apply { mkdirs() }
    private val stageDir = File(root, "staged")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------

    /**
     * Serves take files out of a flat directory, one per legacy take id, so a staged copy can be
     * traced back to the take it came from.
     */
    private inner class FakeStore(
        private val missing: Set<Int> = emptySet(),
        private val sourceAudio: File? = null
    ) : LegacyRecorderStore {
        override fun hasLegacyData() = true
        override fun readProjects(): List<LegacyProject> = emptyList()
        override fun audioSizeBytes(project: LegacyProject) = 0L
        override fun deleteProjectAudio(project: LegacyProject) = true
        override fun sourceAudioFile(project: LegacyProject): File? = sourceAudio

        override fun takeFile(project: LegacyProject, chapterNumber: Int, take: LegacyTake): File? {
            if (take.id in missing) return null
            return File(legacyRoot, "take-${take.id}.wav").also { file ->
                if (!file.isFile) writeWav(file, id = take.id)
            }
        }
    }

    /** A one-frame-per-sample WAV whose every byte is [id]. */
    private fun writeWav(file: File, id: Int, frames: Int = 100) {
        file.parentFile.mkdirs()
        WavFile(file, 1, 44100, 16).let { wav ->
            wav.writer().use { it.write(ByteArray(frames * 2) { id.toByte() }) }
        }
    }

    private fun legacyProject(
        versionSlug: String,
        units: List<LegacyUnit>,
        contributors: String? = null
    ) = LegacyProject(
        id = 1,
        targetLanguageSlug = TARGET.slug,
        sourceLanguageSlug = "en",
        versionSlug = versionSlug,
        bookSlug = BOOK,
        bookNumber = 65,
        mode = LegacyMode.VERSE,
        contributors = contributors,
        sourceAudioPath = null,
        chapters = listOf(LegacyChapter(number = 1, units = units))
    )

    private fun unit(verse: Int, takeIds: List<Int>, chosen: Int? = null) = LegacyUnit(
        startVerse = verse,
        endVerse = verse,
        chosenTakeId = chosen,
        takes = takeIds.mapIndexed { index, id ->
            LegacyTake(id = id, number = index + 1, filename = "take-$id.wav")
        }
    )

    private fun stage(
        projects: List<LegacyProject>,
        store: LegacyRecorderStore = FakeStore(),
        sourceUnits: Map<Int, List<IntRange>> = mapOf(1 to (1..23).map { it..it } + listOf(24..25))
    ): StageLegacyBackup.Result =
        StageLegacyBackup(store, WriteTakeMarkers(), WriteDerivedManifest()).execute(
            stageDir,
            StageLegacyBackup.Request(
                projects = projects,
                targetLanguage = TARGET,
                sourceMetadata = sourceMetadata(),
                bookTitle = "Jude",
                sourceUnits = sourceUnits
            )
        )

    private fun sourceMetadata() = ResourceMetadata(
        conformsTo = "0.2",
        creator = "Door43",
        description = "",
        format = "text/usfm",
        identifier = "ulb",
        issued = LocalDate.now(),
        language = Language("en", "English", "English", "ltr", true, ""),
        modified = LocalDate.now(),
        publisher = "unfoldingWord",
        subject = "Bible",
        type = ContainerType.Bundle,
        title = "Unlocked Literal Bible",
        version = "12-verse",
        license = "",
        path = File(".")
    )

    private fun stagedTakeNames(): List<String> =
        File(stageDir, "${RcConstants.TAKE_DIR}/c01")
            .listFiles()
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    private fun selectedLines(): List<String> =
        File(stageDir, RcConstants.SELECTED_TAKES_FILE)
            .readLines()
            .filter { it.isNotBlank() }

    /** The first sample of a staged take, which identifies the legacy take it was copied from. */
    private fun firstSample(name: String): Int {
        val file = File(stageDir, "${RcConstants.TAKE_DIR}/c01/$name")
        val buffer = ByteArray(2)
        OratureAudioFile(file).reader().use { reader ->
            reader.open()
            reader.getPcmBuffer(buffer)
        }
        return buffer[0].toInt()
    }

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a take is named for its chapter, verse and number`() {
        val result = stage(listOf(legacyProject("ulb", listOf(unit(verse = 3, takeIds = listOf(7))))))

        assertEquals(1, result.takesStaged)
        assertEquals(listOf("aa_reg_jud_c01_v03_t1.wav"), stagedTakeNames())
        assertEquals(7, firstSample("aa_reg_jud_c01_v03_t1.wav"), "the legacy take's own audio")
    }

    @Test
    fun `merged projects number their takes in one sequence`() {
        // The renumbering merging needs: each legacy project numbers its takes from 1, and one unit
        // cannot hold two takes with the same number.
        val result = stage(
            listOf(
                legacyProject("reg", listOf(unit(verse = 1, takeIds = listOf(11, 12)))),
                legacyProject("udb", listOf(unit(verse = 1, takeIds = listOf(21)))),
                legacyProject("ulb", listOf(unit(verse = 1, takeIds = listOf(31))))
            )
        )

        assertEquals(4, result.takesStaged)
        assertEquals(
            listOf(
                "aa_reg_jud_c01_v01_t1.wav",
                "aa_reg_jud_c01_v01_t2.wav",
                "aa_reg_jud_c01_v01_t3.wav",
                "aa_reg_jud_c01_v01_t4.wav"
            ),
            stagedTakeNames()
        )
        // Projects stage in version-slug order, so reg's two takes come first and each take keeps
        // its own audio rather than a neighbour's.
        assertEquals(listOf(11, 12, 21, 31), stagedTakeNames().map(::firstSample))
    }

    @Test
    fun `takes of a bridged verse are named after the unit that covers it`() {
        // Jude's source bridges 24-25 into one unit. A take named v25 would bind to the filler row
        // standing in for the bridged verse, where no unit shows it.
        val result = stage(
            listOf(
                legacyProject(
                    "ulb",
                    listOf(unit(verse = 24, takeIds = listOf(1)), unit(verse = 25, takeIds = listOf(2)))
                )
            )
        )

        assertEquals(2, result.takesStaged)
        assertEquals(
            listOf("aa_reg_jud_c01_v24_t1.wav", "aa_reg_jud_c01_v24_t2.wav"),
            stagedTakeNames(),
            "both verses belong to the unit starting at 24"
        )
    }

    @Test
    fun `a verse no source unit covers is reported, not named`() {
        val result = stage(
            listOf(legacyProject("ulb", listOf(unit(verse = 40, takeIds = listOf(1))))),
            sourceUnits = mapOf(1 to (1..25).map { it..it })
        )

        assertEquals(0, result.takesStaged)
        assertEquals(listOf("ulb c1 v40: no source unit covers it"), result.skipped)
        assertTrue(stagedTakeNames().isEmpty())
    }

    @Test
    fun `only the chosen take of a unit is listed as selected`() {
        stage(
            listOf(
                legacyProject(
                    "ulb",
                    listOf(unit(verse = 2, takeIds = listOf(5, 6), chosen = 6))
                )
            )
        )

        assertEquals(listOf("c01/aa_reg_jud_c01_v02_t2.wav"), selectedLines())
    }

    @Test
    fun `where merged projects both chose a take for one unit, the first wins`() {
        // A unit has one selection, so the second project's choice cannot also be applied.
        stage(
            listOf(
                legacyProject("reg", listOf(unit(verse = 1, takeIds = listOf(11), chosen = 11))),
                legacyProject("udb", listOf(unit(verse = 1, takeIds = listOf(21), chosen = 21)))
            )
        )

        assertEquals(listOf("c01/aa_reg_jud_c01_v01_t1.wav"), selectedLines())
    }

    @Test
    fun `a missing take file is reported and does not consume its number`() {
        val result = stage(
            listOf(legacyProject("ulb", listOf(unit(verse = 1, takeIds = listOf(1, 2, 3))))),
            store = FakeStore(missing = setOf(2))
        )

        assertEquals(2, result.takesStaged)
        assertEquals(1, result.skipped.size, "one report: ${result.skipped}")
        assertEquals(
            listOf("aa_reg_jud_c01_v01_t1.wav", "aa_reg_jud_c01_v01_t2.wav"),
            stagedTakeNames(),
            "take 3's audio takes the number the missing one did not use"
        )
        assertEquals(listOf(1, 3), stagedTakeNames().map(::firstSample))
    }

    @Test
    fun `a staged take carries the verse marker of its unit`() {
        // Import registers a take without reading or adding cues, and a take with no cue is dropped
        // by source-audio export, so the marker has to be written while staging.
        stage(listOf(legacyProject("ulb", listOf(unit(verse = 24, takeIds = listOf(1))))))

        val file = File(stageDir, "${RcConstants.TAKE_DIR}/c01/aa_reg_jud_c01_v24_t1.wav")
        val markers = OratureAudioFile(file).getMarker<VerseMarker>()
        // The bridged unit's own label, which is what a bridged verse reads as everywhere else.
        assertEquals(listOf("24-25"), markers.map { it.label })
        assertEquals(listOf(0), markers.map { it.location })
    }

    @Test
    fun `the container carries the files import requires`() {
        stage(listOf(legacyProject("ulb", listOf(unit(verse = 1, takeIds = listOf(1))))))

        assertTrue(File(stageDir, "manifest.yaml").isFile, "manifest")
        assertTrue(File(stageDir, RcConstants.SELECTED_TAKES_FILE).isFile, "resumable-project marker")
        assertTrue(File(stageDir, RcConstants.PROJECT_MODE_FILE).isFile, "project mode")
        assertTrue(File(stageDir, RcConstants.SOURCE_DIR).isDirectory, "source dir, listed by import")
        assertEquals(
            """{"mode":"DIALECT"}""",
            File(stageDir, RcConstants.PROJECT_MODE_FILE).readText()
        )
    }

    @Test
    fun `legacy source audio is carried in under its own name`() {
        val archive = File(root, "aoh.tr").apply { writeText("opaque legacy container") }

        val result = stage(
            listOf(legacyProject("ulb", listOf(unit(verse = 1, takeIds = listOf(1))))),
            store = FakeStore(sourceAudio = archive)
        )

        assertEquals(listOf("aoh.tr"), result.sourceAudio)
        assertEquals(
            "opaque legacy container",
            File(stageDir, "${RcConstants.SOURCE_AUDIO_DIR}/aoh.tr").readText()
        )
    }

    @Test
    fun `staging again starts from an empty container`() {
        // A resumed run must not inherit takes from an attempt that failed halfway, which would
        // leave names in place that nothing points at.
        stage(listOf(legacyProject("ulb", listOf(unit(verse = 1, takeIds = listOf(1, 2))))))
        assertEquals(2, stagedTakeNames().size)

        stage(listOf(legacyProject("ulb", listOf(unit(verse = 1, takeIds = listOf(3))))))

        assertEquals(listOf("aa_reg_jud_c01_v01_t1.wav"), stagedTakeNames())
        assertEquals(listOf(3), stagedTakeNames().map(::firstSample))
    }

    private companion object {
        const val BOOK = "jud"
        val TARGET = Language("aa", "Qafar af", "Afar", "ltr", false, "")
    }
}
