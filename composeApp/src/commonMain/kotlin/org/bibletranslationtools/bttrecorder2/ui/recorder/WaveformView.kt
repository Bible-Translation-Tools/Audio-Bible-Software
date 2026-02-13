package org.bibletranslationtools.bttrecorder2.ui.recorder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.isActive
import org.bibletranslationtools.otter.common.recorder.ActiveRecordingRenderer

@Composable
fun WaveformView(
    renderer: ActiveRecordingRenderer?,
    modifier: Modifier = Modifier,
    waveColor: Color = Color.Red, // Default to red as in old app mostly
    backgroundColor: Color = Color.White
) {
    var trigger by remember { mutableStateOf(0L) }

    // Animation loop
    LaunchedEffect(renderer) {
        if (renderer != null) {
            while (isActive) {
                withFrameNanos {
                    trigger = it
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        // Access trigger to ensure recomposition on every frame
        trigger

        drawRect(color = backgroundColor)

        if (renderer == null) return@Canvas

        val width = size.width.toInt()
        val height = size.height

        val buffer = renderer.floatBuffer.array
        // The buffer contains pairs of (min, max) for each x position.
        // We iterate through them and draw lines.
        // The buffer size is width * 2. 
        // We need to map the values (0..1?? or raw PCM?) to height.
        // PCMCompressor sends "U.getValueForScreen" which scales logic.
        // Note: ActiveRecordingRenderer uses pcmCompressor.add(short.toFloat()) 
        // We'll need to verify the scaling. 
        // Assuming values are normalized or we need to normalize.
        // floatBuffer in ActiveRecordingRenderer seems to store raw floats from the short?
        // Wait, PCMCompressor logic:
        // `ringBuffer.add(min)` `ringBuffer.add(max)`
        // It doesn't seem to scale in PCMCompressor unless U.getValueForScreen was used (old code used it).
        // My new ActiveRecordingRenderer just does `pcmCompressor.add(short.toFloat())`.
        // So the values are Short.MIN_VALUE to Short.MAX_VALUE (-32768 to 32767).

        // Center is height / 2.
        // Scale factor: height / 2 / 32768.

        val midY = height / 2f
        val scale = height / 2f / 32768f

        for (x in 0 until width) {
            // Buffer structure: [min0, max0, min1, max1, ...]
            // Check bounds just in case
            val idx = x * 2
            if (idx + 1 < buffer.size) {
                val minRaw = buffer[idx]
                val maxRaw = buffer[idx + 1]

                // Draw line from min to max
                // Note: positive audio value usually means "up" visually, but in canvas Y grows down.
                // So +val -> midY - (val * scale)
                //    -val -> midY - (val * scale)
                
                val y1 = midY - (minRaw * scale)
                val y2 = midY - (maxRaw * scale)

                drawLine(
                    color = waveColor,
                    start = Offset(x.toFloat(), y1),
                    end = Offset(x.toFloat(), y2)
                )
            }
        }
        
        // Draw center line
        drawLine(
            color = Color.Gray,
            start = Offset(0f, midY),
            end = Offset(size.width, midY)
        )
    }
}
