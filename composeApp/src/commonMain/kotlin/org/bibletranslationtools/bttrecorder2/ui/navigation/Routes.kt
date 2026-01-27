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
data class ChapterListRoute(val workbookSourceId: Int, val workbookTargetId: Int)

@Serializable
data class UnitListRoute(val workbookSourceId: Int, val workbookTargetId: Int, val chapterNumber: Int)