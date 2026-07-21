package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.ui.OratureColors
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.home
import org.bibletranslationtools.orature.resources.info
import org.bibletranslationtools.orature.resources.settings

/** Which nav-rail destination is currently active, so the rail can highlight it. */
enum class OratureNavDestination {
    HOME, SETTINGS, INFO
}

/**
 * Orature's persistent left nav rail: Home pinned at the top, Settings + Info pinned at
 * the bottom, matching the real app's AppBar (VBox, primary-colored, icon+label toggle
 * buttons, spacer pushing Settings/Info down). Lives in the home screen's Scaffold for
 * Phase 1; a fuller RootView shell will host it across all screens later.
 */
@Composable
fun OratureNavRail(
    // Null when no rail destination is active (e.g. inside an open project with no drawer),
    // so nothing is highlighted — matching the JVM AppBar where Home is only "selected" on home.
    selected: OratureNavDestination?,
    onHomeClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(OratureColors.Primary)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OratureNavRailItem(
            icon = Icons.Filled.Home,
            label = stringResource(Res.string.home),
            selected = selected == OratureNavDestination.HOME,
            onClick = onHomeClick
        )

        Spacer(modifier = Modifier.weight(1f))

        OratureNavRailItem(
            icon = Icons.Filled.Settings,
            label = stringResource(Res.string.settings),
            selected = selected == OratureNavDestination.SETTINGS,
            onClick = onSettingsClick
        )
        OratureNavRailItem(
            icon = Icons.Filled.Info,
            label = stringResource(Res.string.info),
            selected = selected == OratureNavDestination.INFO,
            onClick = onInfoClick
        )
    }
}

@Composable
private fun OratureNavRailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val pillModifier = if (selected) {
        Modifier.background(OratureColors.OnPrimary.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick)
            .then(pillModifier)
            .padding(PaddingValues(vertical = 10.dp, horizontal = 8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = OratureColors.OnPrimary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label,
                color = OratureColors.OnPrimary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
