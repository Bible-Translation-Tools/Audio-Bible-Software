package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.legacy_migration_progress
import org.bibletranslationtools.shared.resources.legacy_migration_progress_count
import org.jetbrains.compose.resources.stringResource


/**
 * @param legacyMigration while old BTT-Recorder projects are being moved, how many books are done
 *   of how many; null otherwise.
 */
@Composable
fun SplashScreen(legacyMigration: Pair<Int, Int>? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.primary) // Use MaterialTheme color
    ) {
        Column(
            modifier = Modifier
                .wrapContentHeight()
                .align(Alignment.Center) // Center vertically
        ) {

            Text(
                text = "Translation Recorder", // Use shared string resource
                color = MaterialTheme.colorScheme.onPrimary, // Text color based on theme
                fontSize = 32.sp, // Replace with your dimension value
                modifier = Modifier
                    .padding(16.dp) // Replace with your dimension value
                    .align(Alignment.CenterHorizontally) // Center horizontally
            )

            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(16.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )

            legacyMigration?.let { (done, total) ->
                Text(
                    text = stringResource(Res.string.legacy_migration_progress),
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .align(Alignment.CenterHorizontally)
                )
                if (total > 0) {
                    Text(
                        text = stringResource(Res.string.legacy_migration_progress_count, minOf(done + 1, total), total),
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(8.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}
