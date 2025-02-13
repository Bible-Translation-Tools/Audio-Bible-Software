package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.bttrecorder2.ui.screens.Project

@Composable
fun ProjectCard(
    project: Project,
    onProjectClick: (Project) -> Unit,
    onInfoClick: () -> Unit,
    onRecordClick: () -> Unit,
) {
    val cardBgColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val disabledTextColor = Color.LightGray // Replace with your actual color resource

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(cardBgColor)
            .padding(16.dp) // Use dp for dimensions
            .clickable { onProjectClick }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clickable { onProjectClick }, // Make the row clickable as well
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = project.language,
                modifier = Modifier
                    .weight(0.4f)
                    .wrapContentWidth(),
                fontWeight = FontWeight.Bold,
                color = textColor,
                fontSize = 18.sp, // Use sp for text sizes
                textAlign = TextAlign.Start // Use TextAlign for text alignment
            )

            Text(
                text = project.book,
                modifier = Modifier
                    .weight(0.3f)
                    .wrapContentWidth(),
                color = textColor,
                fontSize = 18.sp,
                textAlign = TextAlign.Start
            )

            ProgressPieView(
                progress = project.progress,
                modifier = Modifier
                    .size(48.dp), // Use dp for icon sizes
                strokeWidth = 0f,
                strokeColor = Color.Transparent,
                progressColor = primaryColor,
                backgroundColor = disabledTextColor
            )

            Row(
                modifier = Modifier
                    .weight(0.3f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onInfoClick) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Info",
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray // Set tint if needed
                    )
                }

                IconButton(onClick = onRecordClick) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Record",
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray // Set tint if needed
                    )
                }
            }
        }
    }
}