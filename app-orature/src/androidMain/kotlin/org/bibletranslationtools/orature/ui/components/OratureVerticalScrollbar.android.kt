package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Android touch lists don't use a persistent scrollbar; no-op.
@Composable
actual fun OratureVerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier
) {
}
