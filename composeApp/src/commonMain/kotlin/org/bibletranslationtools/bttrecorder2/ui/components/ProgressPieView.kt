package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun ProgressPieView(
    progress: Int, // 0 to 100
    modifier: Modifier = Modifier,
    strokeWidth: Float = 0f, // Use 0f for filled pie
    strokeColor: Color = Color.Transparent,
    progressColor: Color = Color.Blue,
    backgroundColor: Color = Color.LightGray
) {
    val progressDegrees = (progress / 100f) * 360f

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2

        // Background circle
        drawCircle(
            color = backgroundColor,
            center = center,
            radius = radius
        )

        // Progress arc
        drawArc(
            color = progressColor,
            startAngle = -90f, // Start from top
            sweepAngle = progressDegrees,
            useCenter = true, // Fill the pie
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = if (strokeWidth > 0f) Stroke(width = strokeWidth, cap = StrokeCap.Round) else androidx.compose.ui.graphics.drawscope.Fill // Use stroke if width > 0
        )

        if (strokeWidth > 0f && strokeColor != Color.Transparent) {
            drawArc(
                color = strokeColor,
                startAngle = -90f,
                sweepAngle = progressDegrees,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

    }
}