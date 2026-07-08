package org.bibletranslationtools.orature.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Orature's OWN theme — deliberately distinct from the recorder's branding. Placeholder
// palette for the shell; refined as Orature screens are built (Part B).
@Composable
fun OratureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

/**
 * Root composable for the Orature app — a placeholder shell that proves the module +
 * shared-Koin wiring. Real screens (home, settings, narration, translation…) arrive in
 * Part B; Orature builds its own over the shared engine.
 */
@Composable
fun OratureApp() {
    OratureTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Orature", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Compose port — shell", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
