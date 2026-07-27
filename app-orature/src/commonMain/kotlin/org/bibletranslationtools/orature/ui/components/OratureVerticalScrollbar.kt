package org.bibletranslationtools.orature.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A vertical scrollbar for a [LazyListState]. Desktop draws a real Compose Desktop scrollbar (JVM:
 * the customized table scrollbar); Android is a no-op (touch lists don't use a persistent bar).
 */
@Composable
expect fun OratureVerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier
)
