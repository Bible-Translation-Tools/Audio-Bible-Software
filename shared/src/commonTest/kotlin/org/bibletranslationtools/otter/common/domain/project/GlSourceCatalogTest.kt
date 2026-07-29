package org.bibletranslationtools.otter.common.domain.project

import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalog's contract is mostly about failure behaviour: the shipped `gl_sources.json` is
 * partly stale and `embedded_gl_sources.json` only exists if the build's download task ran, so
 * every read has to degrade rather than throw. Before this was extracted from
 * `ImportProjectUseCase.Companion` it could not be tested at all — the lazies read the Compose
 * `Res` object directly, so there was nothing to substitute.
 */
class GlSourceCatalogTest {

    /** Serves canned bytes per path; any path not in the map fails like a missing resource. */
    private class FakeBundledContent(
        private val files: Map<String, String>
    ) : IBundledContentSource {
        var reads = 0
            private set

        override suspend fun read(path: String): ByteArray = readBlocking(path)

        override fun readBlocking(path: String): ByteArray {
            reads++
            val content = files[path] ?: throw NoSuchElementException("no bundled resource $path")
            return content.encodeToByteArray()
        }
    }

    private val twoSources = """
        [
          {"name":"en_ulb","languageCode":"en","url":"https://example.test/en_ulb.zip"},
          {"name":"es_ulb","languageCode":"es","url":"https://example.test/es_ulb.zip"}
        ]
    """.trimIndent()

    @Test
    fun `parses the shipped source catalog`() {
        val catalog = GlSourceCatalog(
            FakeBundledContent(mapOf(SOURCES_JSON_FILE to twoSources))
        )

        assertEquals(2, catalog.sources.size)
        assertEquals(listOf("en_ulb", "es_ulb"), catalog.sources.map { it.name })
        assertEquals(listOf("en", "es"), catalog.sources.map { it.languageCode })
    }

    @Test
    fun `parses the build-generated embedded manifest`() {
        val catalog = GlSourceCatalog(
            FakeBundledContent(mapOf(EMBEDDED_SOURCES_FILE to """["en_ulb"]"""))
        )

        assertEquals(setOf("en_ulb"), catalog.embeddedSourceNames)
    }

    @Test
    fun `a missing source catalog degrades to no sources`() {
        val catalog = GlSourceCatalog(FakeBundledContent(emptyMap()))

        assertTrue(catalog.sources.isEmpty())
    }

    /**
     * Fails closed: with no manifest, nothing is reported as bundled. Offering a source whose
     * zip is absent would surface in the project wizard and then fail on sideload.
     */
    @Test
    fun `a missing embedded manifest degrades to nothing bundled`() {
        val catalog = GlSourceCatalog(FakeBundledContent(emptyMap()))

        assertTrue(catalog.embeddedSourceNames.isEmpty())
    }

    @Test
    fun `malformed json degrades instead of throwing`() {
        val catalog = GlSourceCatalog(
            FakeBundledContent(
                mapOf(
                    SOURCES_JSON_FILE to "{ not json",
                    EMBEDDED_SOURCES_FILE to "{ not json"
                )
            )
        )

        assertTrue(catalog.sources.isEmpty())
        assertTrue(catalog.embeddedSourceNames.isEmpty())
    }

    /** Both properties are lazy and cached — the catalog is registered as a Koin single. */
    @Test
    fun `each manifest is read at most once`() {
        val content = FakeBundledContent(
            mapOf(
                SOURCES_JSON_FILE to twoSources,
                EMBEDDED_SOURCES_FILE to """["en_ulb"]"""
            )
        )
        val catalog = GlSourceCatalog(content)

        repeat(3) {
            catalog.sources
            catalog.embeddedSourceNames
        }

        assertEquals(2, content.reads)
    }
}
