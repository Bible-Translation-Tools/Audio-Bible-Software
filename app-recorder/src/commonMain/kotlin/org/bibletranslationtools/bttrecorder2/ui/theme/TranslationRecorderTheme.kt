package org.bibletranslationtools.bttrecorder2.ui.theme

import androidx.compose.material.Colors
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

object TranslationRecorderTheme {

    // COLOR DEFINITIONS
    val blue = Color(0xFF0250D3)
    val darkBlue = Color(0xFF003389)
    val lightBlue = Color(0xFFE2F0FF)
    val desaturatedBlue = Color(0xFFF3F7FD)

    val saladGreen = Color(0xFF29C38E)
    val darkSaladGreen = Color(0xFF00905E)
    val lightSaladGreen = Color(0xFF6CF4C5)

    val brightBlue = Color(0xFF45B5E7)
    val brightYellow = Color(0xFFFDD835)
    val strongPink = Color(0xFFC2185B)
    val darkPink = Color(0xFF880E4F)

    val vividRed = Color(0xFFDE1D1D)
    val vividPink = Color(0xFFE91E63)
    val darkModerateLimeGreen = Color(0xFF459751)

    val strongRed = Color(0xFFD30202)
    val verySoftRed = Color(0xFFEA9999)
    val verySoftYellow = Color(0xFFFFE599)
    val desaturatedGreen = Color(0xFF93C47D)
    val darkModerateCyan = Color(0xFF45818E)
    val desaturatedDarkBlue = Color(0xFF085394)

    val pureWhite = Color(0xFFFFFFFF)
    val offWhite = Color(0xFFF3F3F3)
    val lightGray0 = Color(0xFFCCCCCC)
    val gray0 = Color(0xFF999999)
    val darkGray1 = Color(0xFF5F5F5F)
    val darkGray0 = Color(0xFF3C3635)
    val veryDarkGray1 = Color(0xFF333333)
    val veryDarkGray0 = Color(0xFF222222)
    val mostlyBlack = Color(0xFF0A0A0A)

    val menu = Color(0xFF81C784)
    val background = Color(0xFF81D4FA)
    val backgroundImage = Color(0xFFBA68C8)
    val mainImageBG = Color(0xFFFDA93D)
    val red = Color(0xFFCC0000)
    val white = Color(0xFFF7F7F7)
    val black = Color(0xFF000000)
    val transblack = Color(0x99000000) // Note: Alpha value (99) is hex for 153/255
    val transparent = Color(0x00000000)

    val snow = Color(0xA6FFFFFF) // Note: Alpha value (A6) is hex for 166/255
    val frost = Color(0xA6000000) // Note: Alpha value (A6) is hex for 166/255

    val darkPrimaryText = Color(0xFF1C1C1C)
    val darkSecondaryText = Color(0xFF888888)
    val darkTertiaryText = Color(0xFFCCCCCC)
    val playIconBg = saladGreen


    // APP-WIDE COLOR ASSIGNMENTS

    val primary = blue
    val primaryDark = darkBlue
    val secondary = saladGreen
    val tertiary = brightYellow
    val primaryBg = offWhite
    val cardBg = pureWhite
    val textLight = pureWhite
    val textLightDisabled = gray0
    val primaryDarkFont = darkGray0

    val minimapBg = veryDarkGray1
    val minimapTimecode = desaturatedGreen

    val volumeBase = desaturatedDarkBlue
    val volumeLow = darkModerateCyan
    val volumeGood = desaturatedGreen
    val volumeHigh = verySoftYellow
    val volumeClipped = strongRed

    val settingPrefCategory = secondary
    val settingPrefTitle = black
    val settingPrefSummary = darkGray1


    // Compose Material Colors
    val composeColors = Colors(
        primary = primary,
        primaryVariant = primaryDark,
        secondary = secondary,
        secondaryVariant = lightSaladGreen, // Example, adjust as needed
        background = primaryBg,
        surface = cardBg,
        error = vividRed,  // or strongRed
        onPrimary = textLight,
        onSecondary = textLight, // Or a darker color if needed
        onBackground = darkPrimaryText,
        onSurface = darkPrimaryText,
        onError = pureWhite,
        isLight = true // Or false, depending on your default theme
    )

    val LightTranslationRecorderColorScheme = ColorScheme(
        primary = blue,
        onPrimary = pureWhite,
        primaryContainer = lightBlue,
        onPrimaryContainer = darkBlue,
        inversePrimary = lightBlue, // Or a suitable inverse color
        secondary = saladGreen,
        onSecondary = pureWhite,
        secondaryContainer = lightSaladGreen,
        onSecondaryContainer = darkSaladGreen,
        tertiary = brightYellow,
        onTertiary = darkGray0,
        tertiaryContainer = verySoftYellow,
        onTertiaryContainer = darkGray1,
        background = offWhite,
        onBackground = darkPrimaryText,
        surface = pureWhite,
        onSurface = darkPrimaryText,
        surfaceVariant = desaturatedBlue,
        onSurfaceVariant = darkGray1,
        surfaceTint = Color.Transparent, // No tint by default
        inverseSurface = darkGray0,
        inverseOnSurface = pureWhite,
        error = strongRed,
        onError = pureWhite,
        errorContainer = verySoftRed,
        onErrorContainer = darkGray1,
        outline = gray0,
        outlineVariant = lightGray0, // A lighter variant
        scrim = Color(0x66000000), // Semi-transparent black for overlays
        surfaceBright = pureWhite, // Or a slightly brighter variant
        surfaceDim = offWhite, // Or a slightly dimmer variant
        surfaceContainer = desaturatedBlue, // Or a neutral container color
        surfaceContainerHigh = lightBlue, // A slightly elevated container
        surfaceContainerHighest = blue, // The most elevated container
        surfaceContainerLow = desaturatedBlue, // A slightly lower container
        surfaceContainerLowest = desaturatedBlue, // The lowest container
    )

    val DarkTranslationRecorderColorScheme = ColorScheme(
        primary = darkBlue,
        onPrimary = pureWhite,
        primaryContainer = blue,
        onPrimaryContainer = lightBlue,
        inversePrimary = blue, // Or a suitable inverse color
        secondary = darkSaladGreen,
        onSecondary = pureWhite,
        secondaryContainer = saladGreen,
        onSecondaryContainer = lightSaladGreen,
        tertiary = darkGray0,
        onTertiary = brightYellow,
        tertiaryContainer = gray0,
        onTertiaryContainer = verySoftYellow,
        background = veryDarkGray1,
        onBackground = offWhite,
        surface = veryDarkGray0,
        onSurface = offWhite,
        surfaceVariant = mostlyBlack,
        onSurfaceVariant = lightGray0,
        surfaceTint = Color.Transparent, // No tint by default
        inverseSurface = pureWhite,
        inverseOnSurface = veryDarkGray0,
        error = vividRed,
        onError = pureWhite,
        errorContainer = red,
        onErrorContainer = verySoftRed,
        outline = darkGray1,
        outlineVariant = gray0, // A lighter variant
        scrim = Color(0x66000000), // Semi-transparent black for overlays
        surfaceBright = offWhite, // Or a brighter variant
        surfaceDim = veryDarkGray0, // Or a dimmer variant
        surfaceContainer = mostlyBlack, // Or a neutral container color
        surfaceContainerHigh = veryDarkGray0, // A slightly elevated container
        surfaceContainerHighest = darkGray1, // The most elevated container
        surfaceContainerLow = mostlyBlack, // A slightly lower container
        surfaceContainerLowest = mostlyBlack, // The lowest container
    )
}