package org.bibletranslationtools.bttrecorder2.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object SplashScreenRoute

@Serializable
object MainMenuRoute

@Serializable
object ProjectManagementRoute

@Serializable
data class ChapterListRoute(val workbookId: Int)

@Serializable
data class UnitListRoute(val projectName: String, val chapterNumber: Int)