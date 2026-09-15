package org.bibletranslationtools.bttrecorder2.migration

import org.bibletranslationtools.otter.common.data.primitives.ContainerType
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Which source a legacy project resolves to.
 *
 * A legacy project migrates as ULB whatever its `versions.slug` said, so `ulb`, `udb` and `reg` for
 * one book and target language converge on one derived project per recording mode and their takes
 * merge. The source identity therefore carries neither the version slug nor the mode in its
 * identifier: the mode lives in the dublin_core version (`12-verse` / `12-chunk`) while the
 * identifier stays `ulb`.
 *
 * Both halves of that are load-bearing, and each fails in its own way:
 *
 *  - keying the identifier on the version slug looks for a source that does not ship, skipping the
 *    project outright;
 *  - keying it on the mode leaves a migrated project that is not a ULB project;
 *  - ignoring the version resolves everything onto the plain `ulb`, which shares the identifier and
 *    the language and covers all 66 books, silently losing the chunking.
 */
class ModeSourceSelectionTest {

    private val chunkMode = File(
        repoRoot(),
        "shared/src/commonMain/composeResources/files/legacy/en_ulb_chunk.zip"
    )

    // ---- everything migrates as ULB, with the mode in the version -------------------------------

    @Test
    fun `both modes resolve to the plain ulb identifier`() {
        // The legacy version slug selects nothing: a `reg` project is a ULB project.
        assertEquals("ulb", InitializeModeSources.IDENTIFIER)
        LegacyMode.entries.forEach { mode ->
            assertTrue(
                InitializeModeSources.matchesModeSource(
                    metadata("ulb", InitializeModeSources.versionFor(mode)),
                    InitializeModeSources.versionFor(mode)
                ),
                "$mode must resolve to the ulb identifier"
            )
        }
    }

    @Test
    fun `the mode picks the version`() {
        assertEquals("12-verse", InitializeModeSources.versionFor(LegacyMode.VERSE))
        assertEquals("12-chunk", InitializeModeSources.versionFor(LegacyMode.CHUNK))
    }

    @Test
    fun `the two modes never resolve to the same source`() {
        // Sharing a source would collide a chunk-mode and a verse-mode project for one book and
        // language on a single derived project and mix their takes.
        assertNotEquals(
            InitializeModeSources.versionFor(LegacyMode.CHUNK),
            InitializeModeSources.versionFor(LegacyMode.VERSE)
        )
    }

    @Test
    fun `the plain ULB is never mistaken for a mode source`() {
        // `InitializeUlb` imports en/ulb/v12 covering all 66 books, so matching on identifier and
        // language alone would accept it for either mode: every book would report as already
        // covered, skipping the import, and chunk-mode projects would derive from verse text.
        val plainUlb = metadata("ulb", "12")
        InitializeModeSources.VERSIONS.forEach { version ->
            assertFalse(
                InitializeModeSources.matchesModeSource(plainUlb, version),
                "en/ulb/v12 must not match the mode source for v$version"
            )
        }
    }

    @Test
    fun `a mode source is not mistaken for the other mode, or for another language`() {
        val chunk = metadata("ulb", InitializeModeSources.CHUNK_MODE_VERSION)
        assertTrue(
            InitializeModeSources.matchesModeSource(chunk, InitializeModeSources.CHUNK_MODE_VERSION)
        )
        assertFalse(
            InitializeModeSources.matchesModeSource(chunk, InitializeModeSources.VERSE_MODE_VERSION)
        )
        assertFalse(
            InitializeModeSources.matchesModeSource(
                metadata("ulb", InitializeModeSources.CHUNK_MODE_VERSION, language = "es"),
                InitializeModeSources.CHUNK_MODE_VERSION
            ),
            "the mode sources are English"
        )
        assertFalse(
            InitializeModeSources.matchesModeSource(
                metadata("udb", InitializeModeSources.CHUNK_MODE_VERSION),
                InitializeModeSources.CHUNK_MODE_VERSION
            )
        )
        assertFalse(InitializeModeSources.matchesModeSource(null, "12-chunk"))
    }

    @Test
    fun `each mode has a bundled text to build from`() {
        LegacyMode.entries.forEach { mode ->
            val artifact = File(
                repoRoot(),
                "shared/src/commonMain/composeResources/${InitializeModeSources.bundledPathFor(mode)}"
            )
            assertTrue(artifact.isFile, "no bundled text for $mode at ${artifact.path}")
        }
    }

    @Test
    fun `the legacy mode column maps as the old app wrote it`() {
        // The legacy schema writes 'single'/'multi' in modes.type and 'verse'/'chunk' in modes.slug.
        assertEquals(LegacyMode.CHUNK, LegacyMode.fromTypeOrSlug("multi"))
        assertEquals(LegacyMode.CHUNK, LegacyMode.fromTypeOrSlug("chunk"))
        assertEquals(LegacyMode.VERSE, LegacyMode.fromTypeOrSlug("single"))
        assertEquals(LegacyMode.VERSE, LegacyMode.fromTypeOrSlug("verse"))
        // A missing mode row must not silently become CHUNK, which would merge unmerged audio.
        assertEquals(LegacyMode.VERSE, LegacyMode.fromTypeOrSlug(null))
    }

    // ---- merging: several legacy projects, one destination ---------------------------------------

    @Test
    fun `legacy projects for one book and language converge per mode`() {
        // aa/ulb/1ch, aa/udb/1ch and aa/reg/1ch in verse mode are one destination; the same three
        // in chunk mode are a second. Six legacy projects, two migrated projects.
        val legacy = listOf("ulb", "udb", "reg").flatMap { version ->
            LegacyMode.entries.map { legacyProject(version, it) }
        }
        assertEquals(6, legacy.size)

        val destinations = legacy
            .map { Triple(it.targetLanguageSlug, it.bookSlug, InitializeModeSources.versionFor(it.mode)) }
            .distinct()

        assertEquals(2, destinations.size, destinations.toString())
        assertEquals(
            setOf(
                Triple("aa", "1ch", InitializeModeSources.VERSE_MODE_VERSION),
                Triple("aa", "1ch", InitializeModeSources.CHUNK_MODE_VERSION)
            ),
            destinations.toSet()
        )
    }

    @Test
    fun `each legacy project keeps its own ledger identity while merging`() {
        // Migration runs per legacy project, deleting each one's audio only once its own takes are
        // copied, so three projects merging into one destination still need three keys.
        val keys = listOf("ulb", "udb", "reg")
            .map { legacyProject(it, LegacyMode.VERSE).key }
        assertEquals(3, keys.distinct().size, keys.toString())
        // The legacy uniqueness rule is (book, target_language, version), so the key carries the
        // version slug even though it selects no source.
        assertTrue(keys.all { it.contains("1ch") && it.startsWith("aa/") })
    }

    // ---- the source is trimmed to the books actually needed -------------------------------------

    @Test
    fun `trimming the manifest keeps only the requested books`() {
        val trimmed = InitializeModeSources.trimManifest(bundledManifest(), setOf("1ch"))

        val projects = trimmed.substringAfter("projects:")
        val identifiers = Regex("""^\s*identifier:\s*'(\w+)'\s*$""", RegexOption.MULTILINE)
            .findAll(projects)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(listOf("1ch"), identifiers, "only the requested book should remain")
        assertTrue(projects.contains("path: './13-1CH.usfm'"))
    }

    @Test
    fun `trimming the manifest leaves the identity intact`() {
        val trimmed = InitializeModeSources.trimManifest(bundledManifest(), setOf("jas"))

        // Trimming is the only on-device rewrite, the identity being stamped at build time, so
        // losing it here would make the import unresolvable.
        assertTrue(trimmed.contains("identifier: 'ulb'"), "dublin_core identifier")
        assertTrue(trimmed.contains("version: '12-chunk'"), "dublin_core version")
        assertTrue(trimmed.contains("identifier: 'en'"), "language block")
        assertTrue(trimmed.trimEnd().endsWith("categories: [ 'bible-nt' ]"), trimmed.takeLast(80))
    }

    @Test
    fun `trimming to an unknown book yields no projects rather than all of them`() {
        val manifest = """
            dublin_core:
              identifier: 'ulb'
              version: '12-chunk'
            projects:

              -
                title: 'James'
                identifier: 'jas'
                path: './60-JAS.usfm'
        """.trimIndent()

        val trimmed = InitializeModeSources.trimManifest(manifest, setOf("nope"))

        assertTrue(trimmed.contains("version: '12-chunk'"))
        assertFalse(trimmed.contains("'jas'"), "an unmatched book must be dropped, not kept")
    }

    @Test
    fun `a manifest without a projects section is returned untouched`() {
        val manifest = "dublin_core:\n  identifier: 'ulb'\n"

        assertEquals(manifest, InitializeModeSources.trimManifest(manifest, setOf("jas")))
    }

    // ---- the label the project info dialog shows ------------------------------------------------

    @Test
    fun `the migrated identifier has a localized Translation Type label`() {
        // ProjectInfoDialog.formatTranslationType maps an identifier to a localized name, and a
        // migrated project arrives as plain `ulb` in either mode. Invoking a private @Composable
        // here is awkward, so this asserts the resource that mapping needs exists.
        val strings = File(
            repoRoot(),
            "shared/src/commonMain/composeResources/values/strings.xml"
        ).readText()

        assertTrue(
            strings.contains("\"info_translation_type_${InitializeModeSources.IDENTIFIER}\""),
            "no label for ${InitializeModeSources.IDENTIFIER}; the dialog would say \"Regular\""
        )
        // No per-mode identifiers exist, so no per-mode labels should either; a stale one would
        // invite re-keying the identifier on the mode.
        assertFalse(strings.contains("info_translation_type_ulbv"))
        assertFalse(strings.contains("info_translation_type_ulbc"))
        // A source imported under any other identifier is named from its container title instead.
        assertTrue(strings.contains("\"value_name_with_code\""))
    }

    // ---------------------------------------------------------------------------------------------

    private fun metadata(
        identifier: String,
        version: String,
        language: String = InitializeModeSources.LANGUAGE
    ) = ResourceMetadata(
        conformsTo = "rc0.2",
        creator = "Door43",
        description = "",
        format = "text/usfm",
        identifier = identifier,
        issued = LocalDate.now(),
        language = Language(language, "", "", "ltr", isGateway = true, region = ""),
        modified = LocalDate.now(),
        publisher = "unfoldingWord",
        subject = "Bible",
        type = ContainerType.Bundle,
        title = "Unlocked Literal Bible",
        version = version,
        license = "",
        path = File(".")
    )

    private fun legacyProject(versionSlug: String, mode: LegacyMode) = LegacyProject(
        id = 1,
        targetLanguageSlug = "aa",
        sourceLanguageSlug = null,
        versionSlug = versionSlug,
        bookSlug = "1ch",
        bookNumber = 13,
        mode = mode,
        contributors = null,
        sourceAudioPath = null,
        chapters = emptyList()
    )

    private fun bundledManifest(): String = ZipFile(chunkMode).use { archive ->
        val entry = archive.entries().asSequence().first { it.name.endsWith("manifest.yaml") }
        archive.getInputStream(entry).use { it.readBytes() }.decodeToString()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not locate the repo root")
        }
        return dir
    }
}
