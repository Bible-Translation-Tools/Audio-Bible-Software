package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.editionBannerUpdate
import org.bibletranslationtools.orature.resources.editionHeldBackChapters
import org.bibletranslationtools.orature.ui.OratureColors
import org.bibletranslationtools.orature.ui.viewmodels.OratureEditionNotice
import org.jetbrains.compose.resources.stringResource

/**
 * Above an open book's chapters: a newer source edition is installed, and which chapters keep an
 * earlier edition's verses. Information only; the edition changes from the home screen (O1-Q4).
 */
@Composable
fun OratureEditionBanner(notice: OratureEditionNotice, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OratureColors.PrimaryLight)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        notice.newer?.let { newer ->
            Text(
                stringResource(Res.string.editionBannerUpdate, sourceEditionText(newer, notice.newerCode)),
                fontSize = 14.sp,
                color = OratureColors.RegularText
            )
        }
        if (notice.heldBackChapters.isNotEmpty()) {
            Text(
                stringResource(Res.string.editionHeldBackChapters, localizedList(notice.heldBackChapters.map { it.toString() })),
                fontSize = 13.sp,
                color = OratureColors.NoteText
            )
        }
    }
}
