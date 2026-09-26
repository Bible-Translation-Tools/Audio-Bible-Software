package org.bibletranslationtools.otter.common.persistence

import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectDirectoryLayoutTest {

    private val userData: File = Files.createTempDirectory("project-layout").toFile()

    @AfterTest
    fun tearDown() {
        userData.deleteRecursively()
    }

    private fun metadata(
        language: String,
        identifier: String,
        version: String,
        creator: String,
        type: ContainerType = ContainerType.Book
    ) = ResourceMetadata(
        conformsTo = "rc0.2", creator = creator, description = "", format = "text/usfm", identifier = identifier,
        issued = LocalDate.of(2017, 11, 29), language = Language(language, language, language, "ltr", false, ""),
        modified = LocalDate.of(2017, 11, 29), publisher = "", subject = "Bible", type = type, title = "",
        version = version, license = "", path = File("/rc")
    )

    private val source = metadata("en", "ulb", "12", "Wycliffe Associates")
    private val target = metadata("fr", "ulb", "12", "Orature")

    private fun File.relative() = relativeTo(userData).invariantSeparatorsPath

    @Test
    fun `a new project's folder has no version and no source creator`() {
        val directory = ProjectDirectoryLayout.resolve(userData, source, target, "jud")

        assertEquals("Orature/en_ulb/fr_ulb/jud", directory.relative())
        assertTrue(directory.isDirectory)
    }

    /** So moving the book to another edition of its source keeps its files where they are. */
    @Test
    fun `the folder is the same for every edition of the source`() {
        val v2407 = target.copy(version = "24-07")

        assertEquals(
            ProjectDirectoryLayout.resolve(userData, source, target, "jud"),
            ProjectDirectoryLayout.resolve(userData, source.copy(version = "24-07"), v2407, "jud")
        )
    }

    @Test
    fun `an existing project keeps its legacy folder`() {
        val legacy = userData.resolve("Orature/Wycliffe Associates/en_ulb/v12/fr/jud").apply { mkdirs() }

        assertEquals(legacy, ProjectDirectoryLayout.resolve(userData, source, target, "jud"))
    }

    /** Two helps in one language (notes and questions) mustn't share a folder. */
    @Test
    fun `helps in the same language get separate folders under the source creator`() {
        val notes = metadata("en", "tn", "1", "Other", ContainerType.Help)
        val questions = metadata("en", "tq", "1", "Other", ContainerType.Help)

        assertEquals("Wycliffe Associates/en_ulb/en_tn/jud", ProjectDirectoryLayout.resolve(userData, source, notes, "jud").relative())
        assertEquals("Wycliffe Associates/en_ulb/en_tq/jud", ProjectDirectoryLayout.resolve(userData, source, questions, "jud").relative())
    }
}
