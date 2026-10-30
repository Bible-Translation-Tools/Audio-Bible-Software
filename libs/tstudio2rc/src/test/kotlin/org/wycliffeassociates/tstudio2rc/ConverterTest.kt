package org.wycliffeassociates.tstudio2rc

import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Checking
import org.wycliffeassociates.resourcecontainer.entity.DublinCore
import org.wycliffeassociates.resourcecontainer.entity.Language
import org.wycliffeassociates.resourcecontainer.entity.Project
import org.wycliffeassociates.resourcecontainer.entity.Source
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConverterTest {

    private lateinit var outputDir: File

    private val dublinCore = DublinCore(
        type = "book",
        conformsTo = "rc0.2",
        format = "text/usfm",
        identifier = "ulb",
        title = "Unlocked Literal Bible",
        subject = "Bible",
        description = "",
        language = Language(
            direction = "ltr",
            identifier = "aac",
            title = "Ari"
        ),
        source = mutableListOf(
            Source(
                identifier = "ulb",
                language = "hi",
                version = "5"
            )
        ),
        rights = "CC BY-SA 4.0",
        creator = "BTT-Writer",
        contributor = mutableListOf("test"),
        relation = mutableListOf(),
        publisher = "Door43",
        issued = LocalDate.now().toString(),
        modified = LocalDate.now().toString(),
        version = "1"
    )
    private val project = Project(
        title = "Jude",
        versification = "ufw",
        identifier = "jud",
        sort = 65,
        path = "./66-JUD.usfm",
        categories = listOf("bible-nt"),
        config = null
    )
    private val checking = Checking(mutableListOf("Wycliffe Associates"), "1")

    @Test
    fun testConvertTsFile() {
        val input = getResourceFile()
        val result = Tstudio2RcConverter.convertFileToRC(input, outputDir)
        try {
            ResourceContainer.load(result).use { rc ->
                assertEquals(dublinCore, rc.manifest.dublinCore)
                assertEquals(mutableListOf(project), rc.manifest.projects)
                assertEquals(checking, rc.manifest.checking)

                rc.accessor.getReader("66-JUD.usfm")
                    .use {
                        val bookText = it.readText()
                        assertEquals(getSampleBookContent(), bookText)
                    }
            }
        } finally {
            result.delete()
        }
    }

    @Test
    fun testConvertTsDir() {
        val tsFile = getResourceFile()
        val unzipDir = outputDir.resolve("tstudio-dir").apply { mkdir() }
        unzipFile(tsFile, unzipDir)
        val inputDir = unzipDir.walk().first { isBookFolder(it.invariantSeparatorsPath) }

        val result = Tstudio2RcConverter.convertDirToRC(inputDir, outputDir)
        try {
            ResourceContainer.load(result).use { rc ->
                assertEquals(dublinCore, rc.manifest.dublinCore)
                assertEquals(mutableListOf(project), rc.manifest.projects)
                assertEquals(checking, rc.manifest.checking)

                rc.accessor.getReader("66-JUD.usfm")
                    .use {
                        val bookText = it.readText()
                        assertEquals(getSampleBookContent(), bookText)
                    }
            }
        } finally {
            result.delete()
        }
    }

    @Test
    fun validFormatAcceptsTstudioArchive() {
        assertTrue(Tstudio2RcConverter.isValidFormat(getResourceFile()))
    }

    @Test
    fun validFormatAcceptsExtractedDirectory() {
        val unzipDir = outputDir.resolve("ts-extracted").apply { mkdir() }
        unzipFile(getResourceFile(), unzipDir)
        assertTrue(Tstudio2RcConverter.isValidFormat(unzipDir))
    }

    @Test
    fun validFormatRejectsLegacyRecorderZip() {
        // A legacy BTT Recorder export also contains a file named manifest.json, but with a
        // completely different schema. It must not be misdetected as tstudio.
        val recorderManifest = """
            {"language":{"slug":"aa","name":"Afar"},
             "book":{"slug":"gen","name":"Genesis","number":1},
             "version":{"slug":"reg","name":"Regular"},
             "anthology":{"slug":"ot","name":"Old Testament"},
             "mode":{"slug":"chunk","name":"Chunk","type":"chunk"},
             "manifest":[],"users":[]}
        """.trimIndent()
        val zip = makeZip("manifest.json" to recorderManifest)
        assertFalse(Tstudio2RcConverter.isValidFormat(zip))
    }

    @Test
    fun validFormatRejectsUnrelatedManifestJson() {
        // A zip that merely contains a file named manifest.json (the old substring match) is
        // not a tstudio project.
        val zip = makeZip("some/dir/manifest.json" to """{"foo":"bar"}""")
        assertFalse(Tstudio2RcConverter.isValidFormat(zip))
    }

    private fun makeZip(vararg entries: Pair<String, String>): File {
        val zip = outputDir.resolve("test-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    @BeforeTest
    fun setup() {
        outputDir = createTempDirectory("test").toFile()
    }

    @AfterTest
    fun cleanUp() {
        outputDir.deleteRecursively()
    }

    private fun getResourceFile(): File {
        val path = javaClass.classLoader.getResource("aac_jud_text_ulb.tstudio")!!.file
        return File(path)
    }

    private fun getSampleBookContent(): String {
        return File(
            javaClass.classLoader.getResource("66-JUD.usfm")!!.file
        ).readText()
    }
}