package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_close
import org.jetbrains.compose.resources.stringResource

/**
 * The export dialogs' shared frame: a title with a close-circle button, the body, and an
 * optional footer under a divider. Without [onClose] the dialog can't be dismissed at all.
 */
@Composable
internal fun DialogFrame(
    title: String,
    onClose: (() -> Unit)?,
    maxWidth: Dp = 560.dp,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = { onClose?.invoke() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = onClose != null,
            dismissOnClickOutside = onClose != null
        )
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(0.92f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Filled.Cancel,
                                contentDescription = stringResource(Res.string.action_close),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
                content()
                if (footer != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        content = footer
                    )
                }
            }
        }
    }
}

/** The frame's plain body text, padded to line up with the title. */
@Composable
internal fun DialogMessage(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = color,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
    )
}
