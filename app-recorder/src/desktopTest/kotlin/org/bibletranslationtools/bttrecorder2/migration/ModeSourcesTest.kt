package org.bibletranslationtools.bttrecorder2.migration

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Asserts against the real artifacts `gradle/generate-mode-sources.gradle.kts` produces, which are
 * what ships and what the recorder imports. That task is a dependency of every `Test` task, so they
 * are in place when this runs.
 *
 * The merge itself is verified by the task, which fails the build unless all 1189 legacy chapters
 * reproduce. These tests cover what migration additionally depends on: where the artifacts live,
 * what identity they carry, and that the legacy chunking aligns with the ULB text verse by verse,
 * which is what keeps migrated audio attached to a unit describing the same verses.
 */
class ModeSourcesTest {

    // Build-only inputs, kept outside composeResources so neither is packed into an app.
    private val template = File(repoRoot(), "shared/legacy-ulb/en_ulb_v12.zip")
    private val definitionsFile = File(repoRoot(), "shared/legacy-chunks/legacy-chunks.json")

    // Generated into files/legacy/ — packed, but imported only once migration finds legacy data.
    private val generated = File(repoRoot(), "shared/src/commonMain/composeResources/files/legacy")
    private val verseMode = generated.resolve("en_ulb_verse.zip")
    private val chunkMode = generated.resolve("en_ulb_chunk.zip")

    private val definitions by lazy { parseDefinitions(definitionsFile.readText()) }

    @Test
    fun `both mode sources are generated, and the inputs stay out of the app`() {
        assertTrue(verseMode.isFile, "missing ${verseMode.name}; run :shared:generateModeSources")
        assertTrue(chunkMode.isFile, "missing ${chunkMode.name}; run :shared:generateModeSources")
        // Packing the template would add ~1.4 MB to both apps for nothing.
        assertFalse(template.path.contains("composeResources"), "template must not be packed")
        assertFalse(
            definitionsFile.path.contains("composeResources"),
            "definitions must not be packed"
        )
        assertTrue(template.isFile, "missing template at ${template.path}")
        assertTrue(definitionsFile.isFile, "missing definitions at ${definitionsFile.path}")
    }

    @Test
    fun `the mode sources are not in files-content, so the wizard never offers them`() {
        // generateEmbeddedSourcesManifest enumerates `files/content`, which the project wizard
        // offers for sideload. From `files/legacy` these ship but stay inert until migration.
        val content = File(repoRoot(), "shared/src/commonMain/composeResources/files/content")
        assertFalse(content.resolve(verseMode.name).exists(), "must not be in files/content")
        assertFalse(content.resolve(chunkMode.name).exists(), "must not be in files/content")
        assertTrue(verseMode.path.contains("files/legacy"))
        assertTrue(chunkMode.path.contains("files/legacy"))
    }

    @Test
    fun `the bundled paths match what the importer asks for`() {
        // A mismatch stays invisible until a migration looks for a source and finds nothing.
        LegacyMode.entries.forEach { mode ->
            val name = InitializeModeSources.bundledNameFor(mode)
            assertEquals(
                "files/legacy/$name.zip",
                InitializeModeSources.bundledPathFor(mode)
            )
            assertTrue(
                generated.resolve("$name.zip").isFile,
                "no artifact for $mode"
            )
        }
    }

    @Test
    fun `the definitions cover every legacy chapter`() {
        assertEquals(66, definitions.size, "66 books, from chunks/{nt,ot}")
        assertEquals(
            DEFINED_CHAPTERS,
            definitions.values.sumOf { it.size },
            "a truncated definitions file would silently change the chunking"
        )
    }

    @Test
    fun `both are ulb and the version is what distinguishes them`() {
        // Every legacy project migrates as ULB, so the mode rides on the version rather than the
        // identifier. Titles still differ, the wizard listing sources by title.
        manifest(verseMode).let {
            assertTrue(it.contains("identifier: 'ulb'"), "must be plain ulb")
            assertTrue(it.contains("version: '12-verse'"), "version is the axis, not identifier")
            assertTrue(it.contains("title: 'Unlocked Literal Bible - Verse Mode'"))
        }
        manifest(chunkMode).let {
            assertTrue(it.contains("identifier: 'ulb'"), "must be plain ulb")
            assertTrue(it.contains("version: '12-chunk'"))
            assertTrue(it.contains("title: 'Unlocked Literal Bible - Chunk Mode'"))
        }
    }

    @Test
    fun `the generated versions are the ones the importer looks for`() {
        assertEquals(
            InitializeModeSources.VERSE_MODE_VERSION,
            versionOf(verseMode),
            "a mismatch makes every verse-mode project unresolvable"
        )
        assertEquals(InitializeModeSources.CHUNK_MODE_VERSION, versionOf(chunkMode))
        // Neither may be the plain ULB's version, which would collide with it in the database key
        // and in the source directory instead of sitting alongside it.
        assertFalse(InitializeModeSources.VERSIONS.contains("12"))
        assertEquals(2, InitializeModeSources.VERSIONS.distinct().size)
    }

    @Test
    fun `verse mode changes only the manifest`() {
        ZipFile(template).use { source ->
            ZipFile(verseMode).use { derived ->
                val books = source.entries().asSequence().filter { it.name.endsWith(".usfm") }.toList()
                assertEquals(66, books.size)
                books.forEach { entry ->
                    val before = source.getInputStream(entry).use { it.readBytes() }
                    val after = derived.getEntry(entry.name)
                        ?.let { derived.getInputStream(it).use { s -> s.readBytes() } }
                    assertTrue(before.contentEquals(after), "${entry.name} was modified")
                }
            }
        }
    }

    @Test
    fun `the template has no bridged verses, which is what makes exact chunking possible`() {
        // Revisions before en_ulb 65fcaa1513 bridge verses across a legacy chunk boundary, such as
        // 1CH 4 `\v 17-18` against a chunk ending at 17, and a bridged verse cannot be split by any
        // rewrite. The `v12` tag still carries 7 such bridges.
        assertEquals(0, bridgedVerseCount(template), "template must have the bridges removed")
    }

    @Test
    fun `chunk mode merges James chapter 1 into the legacy chunk ranges`() {
        val ranges = versesByChapter(chunkMode, "60-JAS.usfm")

        // chunks/nt/jas/chunks.json: {1,3}, {4,5}, {6,8}, {9,11}, {12,13}, {14,16}, {17,18}, ...
        assertEquals(
            listOf(1 to 3, 4 to 5, 6 to 8, 9 to 11, 12 to 13, 14 to 16, 17 to 18),
            ranges.getValue(1).take(7)
        )
    }

    @Test
    fun `chunk mode reproduces the legacy chunking for every one of the 1189 chapters`() {
        val bySlug = usfmBySlug(chunkMode)
        var compared = 0
        val differing = mutableListOf<String>()

        definitions.forEach { (slug, chapters) ->
            val entry = bySlug[slug] ?: return@forEach
            val actual = versesByChapter(chunkMode, entry)
            chapters.forEach { (chapter, ranges) ->
                compared++
                val expected = ranges.map { it.first to it.last }
                val covered = ranges.flatMapTo(mutableSetOf()) { it }
                // Verses the legacy lists skip entirely become their own units, so they must not
                // count against the comparison.
                val produced = actual[chapter]?.filterNot { (a, b) -> a == b && a !in covered }
                if (produced != expected) differing.add("$slug ch$chapter")
            }
        }

        assertEquals(DEFINED_CHAPTERS, compared)
        assertEquals(emptyList(), differing.sorted(), "chapters not reproducing the legacy chunking")
    }

    /**
     * Verse-level alignment between the legacy chunk definitions and the ULB text.
     *
     * Chapter-level range equality is not sufficient on its own: a chunk could name a verse the text
     * does not have, start or end somewhere that is not a verse boundary, or imply a different verse
     * count than the legacy app itself used. Each would silently attach migrated audio to the wrong
     * unit, so all of them are checked across every chapter.
     */
    @Test
    fun `every legacy chunk aligns with the template at verse level`() {
        val bySlug = usfmBySlug(template)
        val ghostVerses = mutableListOf<String>()   // chunk names a verse the text lacks
        val badStarts = mutableListOf<String>()     // chunk does not begin on a verse boundary
        val badEnds = mutableListOf<String>()       // chunk does not end on a verse boundary
        val countMismatch = mutableListOf<String>() // legacy verse-mode count != ULB verse count
        val nonContiguous = mutableListOf<String>() // ULB numbering has holes
        val overlapping = mutableListOf<String>()   // chunk ranges overlap each other
        val orphans = mutableListOf<String>()       // text verse covered by no chunk
        var audited = 0

        definitions.forEach { (slug, chapters) ->
            val entry = bySlug[slug] ?: return@forEach
            val text = versesByChapter(template, entry)
            chapters.forEach { (chapter, ranges) ->
                audited++
                val verses = text[chapter].orEmpty()
                val present = verses.flatMapTo(mutableSetOf()) { (a, b) -> (a..b) }
                val starts = verses.mapTo(mutableSetOf()) { it.first }
                val ends = verses.mapTo(mutableSetOf()) { it.second }
                val covered = ranges.flatMapTo(mutableSetOf()) { it }
                val where = "$slug ch$chapter"

                if ((covered - present).isNotEmpty()) ghostVerses += "$where ${covered - present}"
                if (ranges.any { it.first !in starts }) badStarts += where
                if (ranges.any { it.last !in ends }) badEnds += where
                // The legacy app's verse mode allocated verses 1..max(lastvs), which has to equal
                // the ULB's verse count or a legacy verse-mode take lands on a unit that does not
                // exist.
                if (ranges.maxOf { it.last } != (present.maxOrNull() ?: 0)) countMismatch += where
                if (present.isNotEmpty() && present != (1..present.max()).toSet()) {
                    nonContiguous += where
                }
                if (ranges.sortedBy { it.first }.zipWithNext()
                        .any { (left, right) -> right.first <= left.last }
                ) {
                    overlapping += where
                }
                (present - covered).forEach { orphans += "$where v$it" }
            }
        }

        assertEquals(DEFINED_CHAPTERS, audited)
        assertEquals(emptyList(), ghostVerses, "chunks naming verses the ULB does not have")
        assertEquals(emptyList(), badStarts, "chunks not starting on a verse boundary")
        assertEquals(emptyList(), badEnds, "chunks not ending on a verse boundary")
        assertEquals(emptyList(), countMismatch, "legacy verse-mode count differs from the ULB's")
        assertEquals(emptyList(), nonContiguous, "ULB verse numbering has holes")
        assertEquals(emptyList(), overlapping, "legacy chunk ranges overlap")

        // 9 verses across 4 chapters that no legacy chunk covers, the legacy generator having
        // recorded a then-bridged verse under its first number only. They become their own units, so
        // nothing is unrecordable. Pinned exactly, so a definitions or template change cannot widen
        // the set unnoticed.
        assertEquals(
            listOf(
                "1ch ch4 v18",
                "1ch ch6 v79",
                "1ch ch8 v18", "1ch ch8 v20", "1ch ch8 v21",
                "1ch ch8 v23", "1ch ch8 v24", "1ch ch8 v25",
                "psa ch68 v13"
            ),
            orphans.sorted(),
            "verses covered by no legacy chunk"
        )
    }

    @Test
    fun `no verse is dropped or invented by the merge`() {
        val bySlug = usfmBySlug(chunkMode)
        var checked = 0
        usfmBySlug(template).forEach { (slug, entry) ->
            val derivedEntry = bySlug[slug] ?: return@forEach
            val before = versesByChapter(template, entry)
            val after = versesByChapter(chunkMode, derivedEntry)
            before.forEach { (chapter, ranges) ->
                val had = ranges.flatMapTo(mutableSetOf()) { (a, b) -> (a..b) }
                val has = after[chapter].orEmpty().flatMapTo(mutableSetOf()) { (a, b) -> (a..b) }
                assertEquals(had, has, "$slug ch$chapter lost or gained verses")
                checked++
            }
        }
        assertEquals(DEFINED_CHAPTERS, checked)
    }

    @Test
    fun `chunk mode carries no section markers`() {
        // Where the definitions disagree with `\s5`, a leftover marker would sit inside a merged
        // verse and surface in the text the parser extracts.
        ZipFile(chunkMode).use { archive ->
            val withMarkers = archive.entries().asSequence()
                .filter { it.name.endsWith(".usfm") }
                .filter { entry ->
                    archive.getInputStream(entry).use { it.readBytes() }
                        .decodeToString()
                        .contains("""\s5""")
                }
                .map { it.name }
                .toList()
            assertTrue(withMarkers.isEmpty(), "\\s5 still present in: $withMarkers")
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun bridgedVerseCount(zip: File): Int = ZipFile(zip).use { archive ->
        archive.entries().asSequence()
            .filter { it.name.endsWith(".usfm") }
            .sumOf { entry ->
                val text = archive.getInputStream(entry).use { it.readBytes() }.decodeToString()
                Regex("""\\v\s+\d+\s*-\s*\d+""").findAll(text).count()
            }
    }

    private fun versionOf(zip: File): String =
        Regex("""^  version: '(.+)'$""", RegexOption.MULTILINE)
            .find(manifest(zip))
            ?.groupValues?.get(1)
            ?: error("no dublin_core version in ${zip.name}")

    private fun manifest(zip: File): String = ZipFile(zip).use { archive ->
        val entry = archive.entries().asSequence().first { it.name.endsWith("manifest.yaml") }
        archive.getInputStream(entry).use { it.readBytes() }.decodeToString()
    }

    private fun usfmBySlug(zip: File): Map<String, String> = ZipFile(zip).use { archive ->
        archive.entries().asSequence()
            .filter { it.name.endsWith(".usfm") }
            .mapNotNull { entry ->
                val text = archive.getInputStream(entry).use { it.readBytes() }.decodeToString()
                Regex("""\\id\s+(\w+)""").find(text)
                    ?.groupValues?.get(1)?.lowercase()
                    ?.let { it to entry.name }
            }
            .toMap()
    }

    private fun versesByChapter(zip: File, entryName: String): Map<Int, List<Pair<Int, Int>>> {
        val text = ZipFile(zip).use { archive ->
            val entry = archive.getEntry(entryName)
                ?: archive.entries().asSequence().first { it.name.endsWith(entryName) }
            archive.getInputStream(entry).use { it.readBytes() }.decodeToString()
        }
        val out = linkedMapOf<Int, MutableList<Pair<Int, Int>>>()
        var chapter: Int? = null
        Regex("""\\c\s+(\d+)|\\v\s+(\d+)(?:\s*-\s*(\d+))?""").findAll(text).forEach { match ->
            when {
                match.groupValues[1].isNotEmpty() -> {
                    chapter = match.groupValues[1].toInt()
                    out.getOrPut(chapter!!) { mutableListOf() }
                }
                match.groupValues[2].isNotEmpty() -> chapter?.let { current ->
                    val start = match.groupValues[2].toInt()
                    val end = match.groupValues[3].takeIf { it.isNotBlank() }?.toInt() ?: start
                    out.getOrPut(current) { mutableListOf() }.add(start to end)
                }
            }
        }
        return out
    }

    /** `{"jas":{"1":"1-3,4-5"}}` -> slug -> chapter -> ranges. */
    private fun parseDefinitions(json: String): Map<String, Map<Int, List<IntRange>>> =
        Regex(""""(\w+)":\{([^}]*)\}""").findAll(json).associate { book ->
            book.groupValues[1] to
                    Regex(""""(\d+)":"([^"]*)"""").findAll(book.groupValues[2]).associate { chapter ->
                        chapter.groupValues[1].toInt() to
                                chapter.groupValues[2].split(",").map { range ->
                                    val bounds = range.split("-")
                                    bounds[0].toInt()..bounds[1].toInt()
                                }
                    }
        }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not locate the repo root")
        }
        return dir
    }

    private companion object {
        /** Chapters the vendored definitions cover; mirrors the Gradle task's own check. */
        const val DEFINED_CHAPTERS = 1189
    }
}
