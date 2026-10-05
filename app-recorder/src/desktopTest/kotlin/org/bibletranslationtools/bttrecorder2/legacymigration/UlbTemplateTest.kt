package org.bibletranslationtools.bttrecorder2.legacymigration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the template files the migration ships, read straight from the Android assets folder.
 *
 * The chunk template is generated once and committed, so nothing rebuilds it; these tests are what
 * catches a template that was regenerated wrongly, replaced, or edited by hand.
 */
class UlbTemplateTest {

    @Test
    fun `the chunk template reproduces every chunk the old app defined`() {
        val chunks = Templates.verses(UlbTemplate.CHUNKS)
        val mismatches = Templates.legacyChunks.flatMap { (book, chapters) ->
            chapters.mapNotNull { (chapter, defined) ->
                val covered = defined.flatMapTo(mutableSetOf()) { it }
                // A verse the definitions skip stays a verse of its own; see the next test.
                val actual = chunks[book]?.get(chapter).orEmpty()
                    .filterNot { it.first == it.last && it.first !in covered }
                "$book $chapter: expected $defined, got $actual".takeIf { actual != defined }
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.take(10).joinToString("\n"))
    }

    @Test
    fun `verses the old app's definitions skip stay verses of their own`() {
        // 1CH 4 is defined as …13-16, 17-17, 19-20…: verse 18 is in no chunk.
        val chapter = Templates.verses(UlbTemplate.CHUNKS).getValue("1ch").getValue(4)
        assertTrue(17..17 in chapter && 18..18 in chapter && 19..20 in chapter, "1CH 4 is $chapter")
    }

    @Test
    fun `the old app's definitions cover every chapter of the Bible`() {
        assertEquals(66, Templates.legacyChunks.size)
        assertEquals(1189, Templates.legacyChunks.values.sumOf { it.size })
    }

    @Test
    fun `the verse template has every verse on its own`() {
        val bridged = Templates.verses(UlbTemplate.VERSES).flatMap { (book, chapters) ->
            chapters.flatMap { (chapter, ranges) -> ranges.filter { it.first != it.last }.map { "$book $chapter:$it" } }
        }
        assertTrue(bridged.isEmpty(), "bridged verses: ${bridged.take(10)}")
    }

    @Test
    fun `both templates hold the same verses`() {
        val verses = Templates.verses(UlbTemplate.VERSES).flatVerses()
        val chunks = Templates.verses(UlbTemplate.CHUNKS).flatVerses()
        assertEquals(verses.keys, chunks.keys, "books and chapters")
        val differing = verses.keys.filter { verses[it] != chunks[it] }
        assertTrue(differing.isEmpty(), "chapters whose verses differ: ${differing.take(10)}")
    }

    @Test
    fun `both templates have the same wording`() {
        // The chunk template only moves verse markers and drops \s5 lines; the words are untouched.
        val verses = Templates.wording(UlbTemplate.VERSES)
        val chunks = Templates.wording(UlbTemplate.CHUNKS)
        assertEquals(verses.keys, chunks.keys)
        val differing = verses.keys.filter { verses[it] != chunks[it] }
        assertTrue(differing.isEmpty(), "books whose wording differs: $differing")
    }

    @Test
    fun `both templates are ULB editions in English`() {
        UlbTemplate.entries.forEach { template ->
            val manifest = Templates.manifest(template)
            assertEquals("ulb", manifest.field("identifier"), "$template identifier")
            assertEquals("en", manifest.languageIdentifier(), "$template language")
            assertEquals("12", manifest.field("version"), "$template version")
            assertEquals(template.title, manifest.field("title"), "$template title")
            assertEquals(66, Templates.verses(template).size, "$template books")
        }
    }

    @Test
    fun `both templates have the bundled ULB's creator`() {
        // An edition can only move to another with the same language, identifier and creator, so
        // this is what lets a migrated book move to the bundled ULB later.
        val bundled = Templates.bundledUlb()
        val creator = Manifest.of(bundled).field("creator")
        UlbTemplate.entries.forEach { template ->
            assertEquals(creator, Templates.manifest(template).field("creator"), "$template creator")
        }
    }

    private fun Map<String, Map<Int, List<IntRange>>>.flatVerses(): Map<String, List<Int>> =
        flatMap { (book, chapters) ->
            chapters.map { (chapter, ranges) -> "$book $chapter" to ranges.flatMap { it.toList() }.sorted() }
        }.toMap()
}

/** Reads the shipped template files from the repo. */
internal object Templates {

    private val assets: File = repoRoot().resolve("app-recorder/src/androidMain/assets")

    private val verseMarkers = Regex("""\\c\s+(\d+)|\\v\s+(\d+)(?:\s*-\s*(\d+))?""")
    private val bookId = Regex("""\\id\s+(\w+)""")

    fun file(template: UlbTemplate): File = assets.resolve(template.assetPath).also {
        assertTrue(it.isFile, "template not found at $it")
    }

    /** Book slug to chapter to the verse ranges its `\v` markers declare, in order. */
    fun verses(template: UlbTemplate): Map<String, Map<Int, List<IntRange>>> =
        usfm(file(template)).mapValues { (_, text) ->
            val chapters = mutableMapOf<Int, MutableList<IntRange>>()
            var chapter: Int? = null
            verseMarkers.findAll(text).forEach { match ->
                if (match.groupValues[1].isNotEmpty()) {
                    chapter = match.groupValues[1].toInt().also { chapters.getOrPut(it) { mutableListOf() } }
                } else chapter?.let { current ->
                    val start = match.groupValues[2].toInt()
                    val end = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: start
                    chapters.getValue(current).add(start..end)
                }
            }
            chapters
        }

    /** Each book's words, with verse markers and `\s5` lines removed and whitespace collapsed. */
    fun wording(template: UlbTemplate): Map<String, String> =
        usfm(file(template)).mapValues { (_, text) ->
            text.lineSequence()
                .filterNot { it.trim() == """\s5""" }
                .joinToString(" ")
                .replace(Regex("""\\v\s+\d+(\s*-\s*\d+)?"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

    fun manifest(template: UlbTemplate): Manifest = Manifest.of(file(template))

    /** The old app's chunk definitions: book slug to chapter to its chunks, in order. */
    val legacyChunks: Map<String, Map<Int, List<IntRange>>> by lazy {
        val file = assets.resolve(UlbTemplate.LEGACY_CHUNKS_ASSET)
        assertTrue(file.isFile, "chunk definitions not found at $file")
        Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, chapters) ->
            chapters.jsonObject.entries.associate { (chapter, ranges) ->
                chapter.toInt() to ranges.jsonPrimitive.content.split(",").map { range ->
                    val (start, end) = range.trim().split("-").map(String::toInt)
                    start..end
                }
            }
        }
    }

    /** The English ULB the app bundles, downloaded by `:shared:downloadGLSources`. */
    fun bundledUlb(): File = repoRoot().resolve("shared/src/commonMain/composeResources/files/content/en_ulb.zip").also {
        assertTrue(it.isFile, "bundled ULB not found at $it; run ./gradlew :shared:generateEmbeddedSourcesManifest")
    }

    /** Book slug (lowercase) to its USFM text. */
    private fun usfm(zip: File): Map<String, String> = ZipFile(zip).use { archive ->
        archive.entries().asSequence()
            .filter { it.name.endsWith(".usfm") }
            .map { archive.getInputStream(it).use { s -> s.readBytes() }.decodeToString() }
            .associateBy { text -> bookId.find(text)!!.groupValues[1].lowercase() }
    }

    /** Tests run with the working directory set to app-recorder/. */
    fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir
    }
}

/** The top-level `dublin_core` fields of a resource container's manifest, read as text. */
internal class Manifest private constructor(private val text: String) {

    /** A two-space-indented `dublin_core` scalar, such as `  version: '12'`. */
    fun field(name: String): String? =
        Regex("""(?m)^  $name:\s*'([^']*)'""").find(text)?.groupValues?.get(1)

    fun languageIdentifier(): String? =
        Regex("""(?m)^  language:\s*\n(?:    .*\n)*?    identifier:\s*'([^']*)'""").find(text)?.groupValues?.get(1)

    companion object {
        fun of(zip: File): Manifest = ZipFile(zip).use { archive ->
            val entry = archive.entries().asSequence().first { it.name.endsWith("manifest.yaml") }
            Manifest(archive.getInputStream(entry).use { it.readBytes() }.decodeToString())
        }
    }
}
