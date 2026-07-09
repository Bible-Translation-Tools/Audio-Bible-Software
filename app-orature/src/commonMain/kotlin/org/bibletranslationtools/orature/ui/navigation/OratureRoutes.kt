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
 * is no intermediate chapter-list screen. Phase 4 routes every open to the narration shell;
 * Phase 6 branches by [org.bibletranslationtools.otter.common.data.primitives.ProjectMode]
 * (adding a translation route). [workbookDescriptorId] is `WorkbookDescriptor.id`.
 */
@Serializable
data class OratureNarrationRoute(val workbookDescriptorId: Int)
