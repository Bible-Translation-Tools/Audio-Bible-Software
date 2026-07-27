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
 * [org.bibletranslationtools.orature.ui.viewmodels.OratureVerseMarkerEditor], so the route itself
 * carries no arguments — the host populates the handoff before navigating.
 */
@Serializable
object OratureVerseMarkerRoute
