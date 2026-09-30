package org.bibletranslationtools.orature.ui.components

import androidx.compose.runtime.Composable
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.listJoin
import org.bibletranslationtools.orature.resources.sizeMegabytes
import org.bibletranslationtools.orature.resources.sizeUnderOneMegabyte
import org.jetbrains.compose.resources.stringResource

/** [items] as one localized list: joined pairwise with the `listJoin` template, so a locale can change it. */
@Composable
fun localizedList(items: List<String>): String {
    if (items.isEmpty()) return ""
    var text = items.first()
    for (item in items.drop(1)) text = stringResource(Res.string.listJoin, text, item)
    return text
}

/** A size on disk in whole megabytes, with the unit from the strings. */
@Composable
fun megabytesText(bytes: Long): String {
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb < 1) stringResource(Res.string.sizeUnderOneMegabyte) else stringResource(Res.string.sizeMegabytes, "${mb.toLong()}")
}
