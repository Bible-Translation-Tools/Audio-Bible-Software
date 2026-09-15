package org.bibletranslationtools.otter.common.domain.resourcecontainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * These constants name locations inside a resource container, so they are part of a published file
 * format: whatever they say ends up in every `.orature` a user exports and shares.
 *
 * Nothing at runtime checks them. An export writes both the manifest's project path and the audio
 * from the same constant, and an import reads the location back out of that manifest, so the format
 * stays self-consistent whatever these say — including a value that names an internal app path. The
 * app's own Compose resource path for bundled sources happens to end in `files/content`, close
 * enough to [RcConstants.MEDIA_DIR] to be substituted for it by a careless edit.
 */
class RcConstantsTest {

    @Test
    fun `container paths do not leak the app's own resource paths`() {
        val containerPaths = mapOf(
            "MEDIA_DIR" to RcConstants.MEDIA_DIR,
            "SOURCE_MEDIA_DIR" to RcConstants.SOURCE_MEDIA_DIR,
            "APP_SPECIFIC_DIR" to RcConstants.APP_SPECIFIC_DIR,
            "TAKE_DIR" to RcConstants.TAKE_DIR,
            "SOURCE_DIR" to RcConstants.SOURCE_DIR,
            "SOURCE_AUDIO_DIR" to RcConstants.SOURCE_AUDIO_DIR,
            "SELECTED_TAKES_FILE" to RcConstants.SELECTED_TAKES_FILE,
            "CHUNKS_FILE" to RcConstants.CHUNKS_FILE,
            "PROJECT_MODE_FILE" to RcConstants.PROJECT_MODE_FILE,
            "CHECKING_STATUS_FILE" to RcConstants.CHECKING_STATUS_FILE,
            "LICENSE_FILE" to RcConstants.LICENSE_FILE
        )

        containerPaths.forEach { (name, path) ->
            assertFalse(
                path.contains("composeResources"),
                "$name is a path inside a resource container and must not name an app resource path"
            )
        }
    }

    @Test
    fun `media and app-specific directories keep their published names`() {
        // Spelled out rather than derived, so a rename has to be a deliberate edit here too.
        assertEquals("content", RcConstants.MEDIA_DIR)
        assertEquals("media", RcConstants.SOURCE_MEDIA_DIR)
        assertEquals(".apps/orature", RcConstants.APP_SPECIFIC_DIR)
    }
}
