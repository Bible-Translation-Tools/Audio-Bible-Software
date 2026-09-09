package org.bibletranslationtools.otter.common.io.zip

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AndroidZipFileReader is pure java.util.zip, so it runs on the desktop JVM tier even though it only
 * ships on Android. Its contract is inherited from NioZipFileReader, which backs the same interface
 * on desktop.
 *
 * The part that matters most here is that [AndroidZipFileReader.list] returns paths [stream] can
 * resolve. Every caller pairs the two — `OngoingProjectImporter.importSources` and
 * `ProjectFilesAccessor.copySourceFiles` both list a directory and then stream each result — so a
 * `list` that returned bare filenames would leave those callers unable to read what they just
 * enumerated. Measured against a zip file system, Nio returns `.apps/orature/source/en_ulb.zip` for
 * `list(".apps/orature/source")`, and that value streams.
 */
class AndroidZipFileReaderTest {

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val zip = File.createTempFile("zip-reader-test", ".zip").apply { deleteOnExit() }
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    /** The shape of an exported project backup, which is what the importer reads. */
    private fun backup(): File = zipOf(
        "manifest.yaml" to "dublin_core:",
        "LICENSE.md" to "license",
        ".apps/" to "",
        ".apps/orature/" to "",
        ".apps/orature/selected.txt" to "",
        ".apps/orature/source/" to "",
        ".apps/orature/source/en_ulb.zip" to "source container bytes",
        ".apps/orature/takes/" to "",
        ".apps/orature/takes/c01/" to "",
        ".apps/orature/takes/c01/take.wav" to "audio"
    )

    @Test
    fun `a listed entry can be streamed`() {
        AndroidZipFileReader(backup()).use { reader ->
            val listed = reader.list(".apps/orature/source").toList()

            assertEquals(1, listed.size, "expected one source container, got $listed")
            assertEquals(
                "source container bytes",
                reader.stream(listed.single()).use { it.readBytes().decodeToString() },
                "the value list() returned must be resolvable by stream()"
            )
        }
    }

    @Test
    fun `listing yields paths from the archive root`() {
        AndroidZipFileReader(backup()).use { reader ->
            assertContentEquals(
                listOf(".apps/orature/source/en_ulb.zip"),
                reader.list(".apps/orature/source").toList()
            )
        }
    }

    @Test
    fun `a listed entry can be opened as a reader`() {
        AndroidZipFileReader(backup()).use { reader ->
            val manifest = reader.list("/").single { it.endsWith("manifest.yaml") }

            assertEquals("dublin_core:", reader.bufferedReader(manifest).use { it.readText() })
        }
    }

    @Test
    fun `listing a directory gives immediate children only, each once`() {
        AndroidZipFileReader(backup()).use { reader ->
            // `takes` and `source` are directories with content of their own; each appears once,
            // and their contents do not leak into the parent's listing.
            assertEquals(
                listOf(
                    ".apps/orature/selected.txt",
                    ".apps/orature/source",
                    ".apps/orature/takes"
                ),
                reader.list(".apps/orature").toList().sorted()
            )
        }
    }

    @Test
    fun `a listed subdirectory can be listed in turn`() {
        AndroidZipFileReader(backup()).use { reader ->
            val takes = reader.list(".apps/orature").single { it.endsWith("takes") }
            val chapter = reader.list(takes).single()

            assertEquals(".apps/orature/takes/c01", chapter)
            assertContentEquals(
                listOf(".apps/orature/takes/c01/take.wav"),
                reader.list(chapter).toList()
            )
        }
    }

    @Test
    fun `the archive root is listable by every spelling of it`() {
        // `InitializeProjects.migrateSourcesToVersion2` lists "." and feeds the result to
        // copyDirectory, so the root must enumerate rather than come back empty.
        AndroidZipFileReader(backup()).use { reader ->
            val expected = listOf(".apps", "LICENSE.md", "manifest.yaml")
            listOf(".", "/", "").forEach { root ->
                assertEquals(expected, reader.list(root).toList().sorted(), "list(\"$root\")")
            }
        }
    }

    @Test
    fun `listing an absent or empty directory yields nothing`() {
        AndroidZipFileReader(backup()).use { reader ->
            assertTrue(reader.list(".apps/orature/nope").toList().isEmpty())
            assertTrue(reader.list("manifest.yaml").toList().isEmpty(), "a file has no children")
        }
    }

    @Test
    fun `a sibling directory sharing a name prefix is not listed`() {
        val zip = zipOf(
            "source/a.txt" to "a",
            "source-audio/b.txt" to "b"
        )

        AndroidZipFileReader(zip).use { reader ->
            assertContentEquals(listOf("source/a.txt"), reader.list("source").toList())
        }
    }

    @Test
    fun `macOS metadata alongside a backup does not reach the source listing`() {
        // Zipping a project on macOS adds a parallel __MACOSX tree whose entries carry the same
        // extensions. They sit outside the listed directory and must stay out of its results.
        val zip = zipOf(
            ".apps/orature/source/en_ulb.zip" to "source container bytes",
            "__MACOSX/.apps/orature/source/._en_ulb.zip" to "resource fork"
        )

        AndroidZipFileReader(zip).use { reader ->
            assertContentEquals(
                listOf(".apps/orature/source/en_ulb.zip"),
                reader.list(".apps/orature/source").toList()
            )
        }
    }

    @Test
    fun `exists distinguishes present entries from absent ones`() {
        AndroidZipFileReader(backup()).use { reader ->
            assertTrue(reader.exists(".apps/orature/selected.txt"))
            assertTrue(reader.exists(".apps/orature/source"), "a directory entry")
            assertFalse(reader.exists(".apps/orature/chunks.json"))
        }
    }
}
