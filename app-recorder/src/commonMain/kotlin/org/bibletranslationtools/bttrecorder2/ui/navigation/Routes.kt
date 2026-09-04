package org.bibletranslationtools.bttrecorder2.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object SplashScreenRoute

@Serializable
object MainMenuRoute

@Serializable
object ProjectManagementRoute

@Serializable
object ProjectWizardRoute

@Serializable
object SettingsRoute

/**
 * M1 login + M2 publish entry point — see WacsPublishScreen. [sourceId]/[targetId] identify the
 * workbook and [chapters] the chapter sorts the user selected under Export Options' "Publish to
 * WACS" type (comma-joined rather than a `List<Int>` nav arg, to keep this a plain-value route);
 * left at their defaults when reached some other way (there is none today), in which case the
 * screen has nothing to publish and says so instead of crashing.
 */
@Serializable
data class WacsPublishRoute(
    val sourceId: Int = -1,
    val targetId: Int = -1,
    val chapters: String = ""
)

@Serializable
object ChapterListRoute

@Serializable
object UnitListRoute

@Serializable
data class RecorderRoute(
    val sourceId: Int,
    val targetId: Int,
    val chapterNumber: Int,
    val unitNumber: Int
)

@Serializable
data class PlaybackRoute(
    val sourceId: Int,
    val targetId: Int,
    val chapterNumber: Int,
    val unitNumber: Int,
    val takeNumber: Int? = null
)
