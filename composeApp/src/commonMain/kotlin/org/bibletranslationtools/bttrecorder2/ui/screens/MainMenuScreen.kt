package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun MainMenuScreen(
    onRecordClick: () -> Unit,
    onFilesClick: () -> Unit,
    language: () -> String,
    book: () -> String
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .weight(0.33f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.secondary)
                .clickable { onFilesClick() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Files Button
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "stringResource(id = R.string.files_content_desc)",
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.secondary)
                    .clickable { onFilesClick() },
                tint = Color.White
            )
        }
        // Record Button
        Column(
            modifier = Modifier
                .weight(0.67f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onRecordClick() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "stringResource(id = R.string.record_content_desc)",
                modifier = Modifier.size(48.dp), // Adjust size as needed
                tint = Color.White
            )

            Text(
                text = language(),
                color = MaterialTheme.colorScheme.onSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )

            Text(
                text = book(),
                color = MaterialTheme.colorScheme.onSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)

            )
        }
    }
}

@Preview()
@Composable
fun DefaultPreview() {
    MainMenuScreen({}, {}, { "Language" }, { "Book" })
}