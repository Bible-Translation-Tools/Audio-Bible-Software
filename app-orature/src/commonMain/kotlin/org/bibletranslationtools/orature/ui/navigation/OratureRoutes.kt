package org.bibletranslationtools.orature.ui.navigation

import kotlinx.serialization.Serializable

// Orature's own type-safe navigation routes. Grows as Part B adds screens
// (settings, project open, narration, translation modes…).
@Serializable
object OratureSplashRoute

@Serializable
object OratureHomeRoute

/**
 * The opened project's mode page. In the JVM app `openWorkbook()` docks directly to the mode
 * page (NarrationPage for narration/dialect, ChunkingTranslationPage for translation) — there
 * is no intermediate chapter-list screen. Home branches on
 * [org.bibletranslationtools.otter.common.data.primitives.ProjectMode]: NARRATION/DIALECT →
 * [OratureNarrationRoute], TRANSLATION → [OratureTranslationRoute]. [workbookDescriptorId] is
 * `WorkbookDescriptor.id`.
 */
@Serializable
data class OratureNarrationRoute(val workbookDescriptorId: Int)

/** The oral-translation mode page (JVM: `ChunkingTranslationPage`). */
@Serializable
data class OratureTranslationRoute(val workbookDescriptorId: Int)

/**
 * The built-in Verse Marker editor (JVM: the standalone marker *plugin*, built in here). Its inputs
 * (compiled take + marker set + source text) are handed off out-of-band via
 * [org.bibletranslationtools.orature.services.OratureVerseMarkerEditor], so the route itself
 * carries no arguments — the host populates the handoff before navigating.
 */
@Serializable
object OratureVerseMarkerRoute

/**
 * M5(a) login + publish entry point — see `OratureWacsPublishScreen`. [workbookDescriptorId]
 * identifies the project and [chapters] the chapter sorts the user selected under the per-book
 * Export dialog's "Publish" type (comma-joined rather than a `List<Int>` nav arg, to keep this a
 * plain-value route); left at their defaults when reached some other way (there is none today), in
 * which case the screen has nothing to publish and says so instead of crashing.
 */
@Serializable
data class OratureWacsPublishRoute(
    val workbookDescriptorId: Int = -1,
    val chapters: String = ""
)

/**
 * M5(a) "Restore from WACS" entry point — see `OratureWacsPullScreen`. Reached from the Projects
 * pane's own button (next to Import); no nav args (unlike [OratureWacsPublishRoute]) since the
 * flow starts by picking a repo, not a project.
 */
@Serializable
object OratureWacsPullRoute
