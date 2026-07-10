package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.ui.OratureColors

/** The narration button variants from Orature's control.css (`btn--primary` / `btn--secondary`). */
enum class NarrationButtonStyle { PRIMARY, SECONDARY }

private val ButtonShape = RoundedCornerShape(12.dp)

/**
 * A narration action button matching Orature's `.btn--primary` / `.btn--secondary`: 48px tall,
 * 12px radius, 20px text, an optional leading icon. [active] gives a secondary button the
 * engaged light-blue fill (JVM `:active` pseudo, e.g. the Pause button while recording).
 */
@Composable
fun NarrationButton(
    text: String,
    icon: ImageVector?,
    style: NarrationButtonStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false
) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }

    when (style) {
        NarrationButtonStyle.PRIMARY -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            shape = ButtonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = OratureColors.Primary,
                contentColor = OratureColors.OnPrimary,
                disabledContainerColor = OratureColors.SurfaceSecondary,
                disabledContentColor = OratureColors.Disabled
            ),
            content = { RowContent(content) }
        )

        NarrationButtonStyle.SECONDARY -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            shape = ButtonShape,
            border = BorderStroke(2.dp, if (enabled) OratureColors.Primary else OratureColors.Disabled),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (active) OratureColors.PrimaryLight else OratureColors.Foreground,
                contentColor = OratureColors.Primary,
                disabledContentColor = OratureColors.Disabled
            ),
            content = { RowContent(content) }
        )
    }
}

@Composable
private fun RowContent(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = { content() }
    )
}
