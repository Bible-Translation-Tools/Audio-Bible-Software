package org.bibletranslationtools.otter.common.domain.resourcecontainer.project

import io.mockk.every
import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.io.zip.IFileReader
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.RcConstants
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `copySourceFiles(IFileReader)` filters what it accepts out of a project's source-audio directory,
 * while the export side does not filter at all. An unrecognised extension therefore exports fine and
 * is dropped on the way back in, which is what would become of a legacy source-audio container
 * without [RcConstants.LEGACY_SOURCE_AUDIO_EXTENSIONS].
 */
class CopySourceAudioFilterTest {

    private val work = createTempDirectory("copy-source-audio").toFile()
    private val sourceAudioDir = File(work, "source/audio").apply { mkdirs() }

    private val language = Language("en", "English", "English", "ltr", isGateway = true, region = "")
    private val metadata = ResourceMetadata(
        conformsTo = "rc0.2",
        creator = "Test",
        description = "",
        format = "text/usfm",
        identifier = "ulb",
        issued = LocalDate.now(),
        language = language,
        modified = LocalDate.now(),
        publisher = "",
        subject = "Bible",
        type = ContainerType.Book,
        title = "Unlocked Literal Bible",
        version = "12",
        license = "",
        path = File(work, "rc")
    )
    private val project = Collection(1, "jas", "book", "James", null)

    private val directoryProvider: IDirectoryProvider = mockk(relaxed = true) {
        every { getProjectDirectory(any(), any(), any<Collection>()) } returns work
        every { getProjectSourceDirectory(any(), any(), any<Collection>()) } returns File(work, "source")
        every { getProjectSourceAudioDirectory(any(), any(), any<String>()) } returns sourceAudioDir
        every { getProjectAudioDirectory(any(), any(), any<Collection>()) } returns File(work, "takes")
    }

    private val accessor = ProjectFilesAccessor(directoryProvider, metadata, metadata, project)

    @AfterTest
    fun tearDown() {
        work.deleteRecursively()
    }

    /** An [IFileReader] over a flat set of `name to bytes` entries in the source-audio directory. */
    private fun reader(vararg entries: Pair<String, String>): IFileReader = mockk(relaxed = true) {
        every { exists(RcConstants.SOURCE_DIR) } returns false
        every { exists(RcConstants.SOURCE_AUDIO_DIR) } returns true
        every { list(RcConstants.SOURCE_AUDIO_DIR) } returns
                entries.asSequence().map { "${RcConstants.SOURCE_AUDIO_DIR}/${it.first}" }
        entries.forEach { (name, body) ->
            every { stream("${RcConstants.SOURCE_AUDIO_DIR}/$name") } returns body.byteInputStream()
        }
    }

    @Test
    fun `a legacy tr container is copied in`() {
        accessor.copySourceFiles(reader("aaa_source.tr" to "aoh!payload"))

        val copied = File(sourceAudioDir, "aaa_source.tr")
        assertTrue(copied.isFile, "the legacy container must survive an import")
        assertEquals("aoh!payload", copied.readText())
    }

    @Test
    fun `real source audio is still copied in`() {
        accessor.copySourceFiles(reader("jas_c01.wav" to "wav", "jas_c01.cue" to "cue"))

        assertTrue(File(sourceAudioDir, "jas_c01.wav").isFile)
        assertTrue(File(sourceAudioDir, "jas_c01.cue").isFile)
    }

    @Test
    fun `unrelated files are still ignored`() {
        accessor.copySourceFiles(reader("notes.txt" to "text", "cover.png" to "image"))

        assertFalse(File(sourceAudioDir, "notes.txt").exists())
        assertFalse(File(sourceAudioDir, "cover.png").exists())
    }

    @Test
    fun `the legacy extension check is case-insensitive`() {
        accessor.copySourceFiles(reader("SOURCE.TR" to "aoh!"))

        assertTrue(File(sourceAudioDir, "SOURCE.TR").isFile)
    }

    @Test
    fun `an existing file is not overwritten`() {
        File(sourceAudioDir, "aaa_source.tr").writeText("original")

        accessor.copySourceFiles(reader("aaa_source.tr" to "replacement"))

        assertEquals("original", File(sourceAudioDir, "aaa_source.tr").readText())
    }

    @Test
    fun `the legacy extensions are opaque containers, not audio or metadata formats`() {
        // Membership means "copy, never interpret": nothing decodes an Archive of Holding, and
        // `SourceAudioAccessor` must not treat one as playable chapter audio.
        assertTrue(RcConstants.isLegacySourceAudio("tr"))
        assertTrue(RcConstants.isLegacySourceAudio("aoh"))
        assertFalse(RcConstants.isLegacySourceAudio("wav"))
        assertFalse(RcConstants.isLegacySourceAudio("mp3"))
    }
}
