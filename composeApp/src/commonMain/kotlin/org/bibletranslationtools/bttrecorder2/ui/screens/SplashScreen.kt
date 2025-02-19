package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.getString // For string resources from shared module


@Composable
fun SplashScreen() {
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
        }
    }
}