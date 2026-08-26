package org.wycliffeassociates.resourcecontainer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.resourcecontainer.entity.MediaManifest
import org.wycliffeassociates.resourcecontainer.entity.checking
import org.wycliffeassociates.resourcecontainer.entity.dublincore
import org.wycliffeassociates.resourcecontainer.entity.manifest
import org.wycliffeassociates.resourcecontainer.entity.project

/**
 * Pins which keys reach a written manifest.
 *
 * The kaml migration had to reproduce two different Jackson inclusion rules by hand, because kaml
 * has no per-instance null/empty inclusion setting: the writer's
 * `setSerializationInclusion(Include.NON_NULL)` and Project's class-level
 * `@JsonInclude(NON_EMPTY)`. Those are now `encodeDefaults = true` plus `@EncodeDefault(NEVER)` on
 * individual properties, which is easy to get subtly wrong in a way no round-trip test would
 * notice — a manifest that reads back correctly can still have gained or lost keys that other
 * tools consume.
 */
class ManifestInclusionTest {

    private fun writeManifest(m: Manifest): String {
        val out = java.io.ByteArrayOutputStream()
        ResourceContainer.writeManifestTo(out, m)
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun `a project omits fields still holding their defaults`() {
        // Was @JsonInclude(NON_EMPTY) on Project.
        val yaml = writeManifest(
            manifest {
                dublinCore = dublincore { identifier = "ulb" }
                checking = checking {}
                projects = listOf(project { identifier = "gen"; path = "./gen.usfm" })
            }
        )

        assertTrue("the set fields must be written", yaml.contains("identifier: \"gen\""))
        assertTrue(yaml.contains("path:"))
        assertFalse("an untouched empty project field must not be written", yaml.contains("versification:"))
        assertFalse(yaml.contains("categories:"))
        assertFalse(yaml.contains("sort:"))
    }

    @Test
    fun `dublin core keeps empty strings`() {
        // Was Include.NON_NULL, which omits only nulls — an empty string was still written, and
        // consumers of manifest.yaml have seen those keys present for its whole history.
        val yaml = writeManifest(
            manifest {
                dublinCore = dublincore { identifier = "ulb" }
                checking = checking {}
                projects = listOf()
            }
        )

        assertTrue("empty-but-present dublin_core keys must survive", yaml.contains("subject:"))
        assertTrue(yaml.contains("rights:"))
    }

    @Test
    fun `a null media resource is omitted rather than written as null`() {
        // Include.NON_NULL dropped this key entirely. kaml writes `resource: null` unless the
        // property opts out, so MediaManifest.resource carries @EncodeDefault(NEVER).
        val out = java.io.ByteArrayOutputStream()
        ResourceContainer.writeMediaTo(out, MediaManifest())
        val yaml = out.toString(Charsets.UTF_8.name())

        assertFalse("a null resource must not appear at all", yaml.contains("resource:"))
    }
}
