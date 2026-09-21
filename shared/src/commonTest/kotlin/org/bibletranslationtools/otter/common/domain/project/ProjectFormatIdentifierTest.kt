package org.bibletranslationtools.otter.common.domain.project

import org.bibletranslationtools.otter.common.OTTER_JSON
import org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.ScriptureBurritoWrapper
import org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.WrapperContents
import org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.WrapperMeta
import org.bibletranslationtools.scriptureburrito.CopyrightSchema
import org.bibletranslationtools.scriptureburrito.Flavor
import org.bibletranslationtools.scriptureburrito.Format
import org.bibletranslationtools.scriptureburrito.IdAuthoritiesSchema
import org.bibletranslationtools.scriptureburrito.IdentificationSchema
import org.bibletranslationtools.scriptureburrito.LanguageSchema
import org.bibletranslationtools.scriptureburrito.Languages
import org.bibletranslationtools.scriptureburrito.MetaVersionSchema
import org.bibletranslationtools.scriptureburrito.ScopeSchema
import org.bibletranslationtools.scriptureburrito.ShortStatement
import org.bibletranslationtools.scriptureburrito.SoftwareAndUserInfoSchema
import org.bibletranslationtools.scriptureburrito.SourceMetaSchema
import org.bibletranslationtools.scriptureburrito.SourceMetadataSchema
import org.bibletranslationtools.scriptureburrito.TypeSchema
import org.bibletranslationtools.scriptureburrito.container.BurritoContainer
import org.bibletranslationtools.scriptureburrito.flavor.FlavorType
import org.bibletranslationtools.scriptureburrito.flavor.scripture.audio.AudioFlavorSchema
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Checking
import org.wycliffeassociates.resourcecontainer.entity.DublinCore
import org.wycliffeassociates.resourcecontainer.entity.Language
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.resourcecontainer.entity.Project
import org.wycliffeassociates.resourcecontainer.entity.Source
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Detection coverage for [ProjectFormatIdentifier].
 *
 * The positive cases build a minimal fixture of each format with that format's own library, so the
 * chain runs against real loaders rather than hand-rolled bytes. The negative cases pin the
 * burrito-wrapper regression: `ScriptureBurritoWrapper.load()` returns null for non-wrappers, but
 * `BurritoWrapperIdentifier` used to report `BURRITO_WRAPPER` unconditionally — which turned that
 * link into a catch-all, left the ScriptureBurrito link unreachable, and stopped unsupported files
 * from ever being reported as unsupported.
 */
class ProjectFormatIdentifierTest {

    private lateinit var tmpDir: File

    @Test
    fun detectsResourceContainer() {
        assertEquals(ProjectFormat.RESOURCE_CONTAINER, ProjectFormatIdentifier.getProjectFormat(resourceContainerZip()))
    }

    @Test
    fun detectsTstudio() {
        assertEquals(ProjectFormat.TSTUDIO, ProjectFormatIdentifier.getProjectFormat(tstudioZip()))
    }

    @Test
    fun detectsScriptureBurrito() {
        assertEquals(ProjectFormat.SCRIPTURE_BURRITO, ProjectFormatIdentifier.getProjectFormat(scriptureBurritoZip()))
    }

    @Test
    fun detectsBurritoWrapper() {
        assertEquals(ProjectFormat.BURRITO_WRAPPER, ProjectFormatIdentifier.getProjectFormat(burritoWrapperZip()))
    }

    @Test
    fun unsupportedFileIsRejectedNotClaimedAsBurritoWrapper() {
        val file = File.createTempFile("not-a-project", ".txt", tmpDir)
        file.writeText("this is not any supported project format")
        assertFailsWith<IllegalArgumentException> {
            ProjectFormatIdentifier.getProjectFormat(file)
        }
    }

    @Test
    fun zipWithoutAKnownManifestIsRejectedNotClaimedAsBurritoWrapper() {
        val zip = zipOf("readme.txt" to "nothing a format identifier should recognise")
        assertFailsWith<IllegalArgumentException> {
            ProjectFormatIdentifier.getProjectFormat(zip)
        }
    }

    private fun resourceContainerZip(): File {
        val zip = newZipPath("resource-container")
        // conformsTo is left blank so create() stamps the library's own expected version (it does
        // that after the init block returns), keeping the strict load() the identifier performs
        // from throwing on a version mismatch — so write() must run after create(), not inside it.
        ResourceContainer.create(zip) {
            manifest = Manifest(
                dublinCore = DublinCore(
                    type = "book",
                    conformsTo = "",
                    format = "text/usfm",
                    identifier = "ulb",
                    title = "Unlocked Literal Bible",
                    subject = "Bible",
                    description = "",
                    language = Language(direction = "ltr", identifier = "en", title = "English"),
                    source = mutableListOf(Source(identifier = "ulb", language = "en", version = "1")),
                    rights = "CC BY-SA 4.0",
                    creator = "test",
                    contributor = mutableListOf(),
                    relation = mutableListOf(),
                    publisher = "test",
                    issued = LocalDate.now().toString(),
                    modified = LocalDate.now().toString(),
                    version = "1"
                ),
                checking = Checking(mutableListOf("test"), "1"),
                projects = mutableListOf(
                    Project(
                        title = "Jude",
                        versification = "ufw",
                        identifier = "jud",
                        sort = 65,
                        path = "./66-JUD.usfm",
                        categories = listOf("bible-nt"),
                        config = null
                    )
                )
            )
        }.use { it.write() }
        return zip
    }

    private fun tstudioZip(): File = zipOf("aac_jud_text_ulb/manifest.json" to TSTUDIO_MANIFEST)

    private fun scriptureBurritoZip(): File {
        val zip = newZipPath("burrito")
        // Mirrors the construction in scripture-burrito's own TestMetadataSubtypes — the smallest
        // metadata that BurritoContainer.load will accept.
        val metadata = SourceMetadataSchema(
            Format.SCRIPTURE_BURRITO,
            SourceMetaSchema(
                dateCreated = Date.from(Instant.now()),
                version = MetaVersionSchema._1_0_0,
                defaultLocale = "en",
                generator = SoftwareAndUserInfoSchema().apply {
                    softwareName = "test"
                    softwareVersion = "1.0.0"
                }
            ),
            IdAuthoritiesSchema(),
            IdentificationSchema(),
            confidential = false,
            copyright = CopyrightSchema().apply {
                shortStatements = mutableListOf(ShortStatement("CC BY-SA", "en"))
            },
            type = TypeSchema(
                FlavorType(
                    name = Flavor.SCRIPTURE,
                    flavor = AudioFlavorSchema(),
                    currentScope = ScopeSchema().apply { this["GEN"] = mutableListOf("1") }
                )
            ),
            languages = Languages().apply { add(LanguageSchema("en")) }
        )
        BurritoContainer.create(zip) {
            manifest = metadata
            write()
        }.close()
        return zip
    }

    private fun burritoWrapperZip(): File {
        val wrapper = ScriptureBurritoWrapper(
            meta = WrapperMeta(
                name = mapOf("en" to "Test wrapper"),
                version = "1.0",
                generator = mapOf("name" to "test"),
                dateCreated = "2024-01-01"
            ),
            format = "scripture burrito wrapper",
            contents = WrapperContents(burritos = emptyList())
        )
        val json = OTTER_JSON.encodeToString(ScriptureBurritoWrapper.serializer(), wrapper)
        return zipOf("metadata.json" to json)
    }

    private fun newZipPath(prefix: String): File =
        File.createTempFile(prefix, ".zip", tmpDir).also { it.delete() }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val zip = File.createTempFile("fmt", ".zip", tmpDir)
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
        tmpDir = createTempDirectory("format-identifier").toFile()
    }

    @AfterTest
    fun cleanUp() {
        tmpDir.deleteRecursively()
    }

    private companion object {
        val TSTUDIO_MANIFEST = """
            {"package_version":7,"format":"usfm",
             "generator":{"name":"ts-desktop","build":"7"},
             "target_language":{"id":"aac","name":"Ari","direction":"ltr"},
             "project":{"id":"jud","name":"Jude"},
             "type":{"id":"text","name":"Text"},
             "resource":{"id":"ulb","name":"Unlocked Literal Bible"},
             "source_translations":[],"parent_draft":{},"translators":[],"finished_chunks":[]}
        """.trimIndent()
    }
}
