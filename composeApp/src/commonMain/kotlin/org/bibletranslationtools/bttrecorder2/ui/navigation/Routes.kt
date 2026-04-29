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
