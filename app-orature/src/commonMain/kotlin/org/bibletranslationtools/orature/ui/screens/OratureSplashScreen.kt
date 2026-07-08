package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.ui.viewmodels.OratureSplashViewModel

@Composable
fun OratureSplashScreen(viewModel: OratureSplashViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Orature", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(
            text = viewModel.progressTitle.ifBlank { "Starting up…" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        if (viewModel.progressBody.isNotBlank()) {
            Text(viewModel.progressBody, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
    }
}
