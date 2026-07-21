package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.orature_splash
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureSplashViewModel
import org.jetbrains.compose.resources.painterResource

// JVM splash-screen.css: title #e6e6e6, body #cccccc; the bespoke splash art is always navy.
private val SplashBackground = Color(0xFF001547)
private val SplashTitle = Color(0xFFE6E6E6)
private val SplashBody = Color(0xFFCCCCCC)

/**
 * The branded startup splash — the JVM's `orature_splash.png` (navy gradient + BTT Orature logo)
 * filling the surface, with a progress bar and status title/body pinned to the bottom (JVM
 * `SplashScreen` + `splash-screen.css`). On desktop this fills a dedicated 576×480 undecorated
 * window; on Android it is the first full-screen route. Progress/title/body come from
 * [OratureSplashViewModel] (InitializeApp's status stream).
 */
@Composable
fun OratureSplashScreen(viewModel: OratureSplashViewModel) {
    Box(modifier = Modifier.fillMaxSize().background(SplashBackground)) {
        Image(
            painter = painterResource(Res.drawable.orature_splash),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // JVM .splash__status: -fx-padding: 0 6em 2em 6em; -fx-spacing: 20px.
                .padding(horizontal = 96.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            LinearProgressIndicator(
                progress = { viewModel.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = OratureColors.Primary,
                trackColor = Color(0x33FFFFFF)
            )
            // JVM .splash__text-block: centered, 5px spacing.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (viewModel.progressTitle.isNotBlank()) {
                    Text(
                        text = viewModel.progressTitle,
                        color = SplashTitle,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
                if (viewModel.progressBody.isNotBlank()) {
                    Text(
                        text = viewModel.progressBody,
                        color = SplashBody,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
