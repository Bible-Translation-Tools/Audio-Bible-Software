package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.runtime.Composable
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.list_join
import org.jetbrains.compose.resources.stringResource

/** [items] as one localized list: joined pairwise with the `list_join` template, so a locale can change it. */
@Composable
fun localizedList(items: List<String>): String {
    if (items.isEmpty()) return ""
    var text = items.first()
    for (item in items.drop(1)) text = stringResource(Res.string.list_join, text, item)
    return text
}
