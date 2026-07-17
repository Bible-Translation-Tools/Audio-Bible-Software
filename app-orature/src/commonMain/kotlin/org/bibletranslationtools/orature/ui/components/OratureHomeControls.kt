package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.options
import org.bibletranslationtools.orature.resources.search
import org.jetbrains.compose.resources.stringResource

private val PillShape = RoundedCornerShape(24.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

/**
 * The book-search bar (JVM: `SearchBar` / filtered-search-bar): a rounded pill with the text on the
 * left and a trailing magnifier separated by a thin divider.
 */
@Composable
fun OratureSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .background(OratureColors.Foreground, PillShape)
            .border(1.dp, OratureColors.SurfaceTertiary, PillShape)
            .padding(start = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(stringResource(Res.string.search), color = OratureColors.NoteText, fontSize = 16.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = OratureColors.RegularText, fontSize = 16.sp),
                cursorBrush = SolidColor(OratureColors.Primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(modifier = Modifier.width(1.dp).height(28.dp).background(OratureColors.SurfaceTertiary))
        Box(modifier = Modifier.padding(horizontal = 14.dp)) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(Res.string.search), tint = OratureColors.RegularText)
        }
    }
}

/**
 * The project-group options button (JVM: `btn btn--icon section-option-button`): a rounded square
 * with a light border and a vertical "⋮". When its menu is open ([active]) it fills dark navy with
 * a white icon (JVM `.btn--icon:active`).
 */
@Composable
fun OratureSectionOptionButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (active) OratureColors.RegularText else OratureColors.Foreground
    val iconTint = if (active) OratureColors.Foreground else OratureColors.RegularText80
    Box(
        modifier = modifier
            .size(48.dp)
            .background(background, ButtonShape)
            .then(if (active) Modifier else Modifier.border(1.dp, OratureColors.SurfaceTertiary, ButtonShape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.options), tint = iconTint)
    }
}
