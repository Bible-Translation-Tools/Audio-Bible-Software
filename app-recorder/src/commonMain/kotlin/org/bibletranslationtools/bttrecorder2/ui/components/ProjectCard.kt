package org.bibletranslationtools.bttrecorder2.ui.components
 
import org.bibletranslationtools.bttrecorder2.ui.MockData
import org.jetbrains.compose.ui.tooling.preview.Preview
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
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.cd_info
import org.bibletranslationtools.shared.resources.cd_record
import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProjectCard(
    workbook: WorkbookDescriptor,
    onWorkbookClick: (WorkbookDescriptor) -> Unit,
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
            .clickable { onWorkbookClick(workbook) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clickable { onWorkbookClick(workbook) }, // Make the row clickable as well
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = workbook.targetLanguage.anglicizedName,
                modifier = Modifier
                    .weight(0.4f)
                    .wrapContentWidth(),
                fontWeight = FontWeight.Bold,
                color = textColor,
                fontSize = 18.sp, // Use sp for text sizes
                textAlign = TextAlign.Start // Use TextAlign for text alignment
            )
 
            Text(
                text = workbook.title,
                modifier = Modifier
                    .weight(0.3f)
                    .wrapContentWidth(),
                color = textColor,
                fontSize = 18.sp,
                textAlign = TextAlign.Start
            )
 
            // Workbook Descriptor progress is a Single<Double>.
            // In a real MVVM setup, the ViewModel should ideally flatten this.
            // For now, we'll stub it or use a default if available.
            ProgressPieView(
                progress = 0, // TODO: Observe Single<Double> progress correctly
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
                        contentDescription = stringResource(Res.string.cd_info),
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray // Set tint if needed
                    )
                }

                IconButton(onClick = onRecordClick) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = stringResource(Res.string.cd_record),
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray // Set tint if needed
                    )
                }
            }
        }
    }
}
@Preview
@Composable
fun ProjectCardPreview() {
    ProjectCard(
        workbook = MockData.mockWorkbooks[0],
        onWorkbookClick = {},
        onInfoClick = {},
        onRecordClick = {}
    )
}
