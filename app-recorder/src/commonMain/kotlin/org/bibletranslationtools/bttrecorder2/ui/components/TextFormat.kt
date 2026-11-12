package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.edition_month_pattern
import org.bibletranslationtools.shared.resources.info_value_unknown
import org.bibletranslationtools.shared.resources.list_join
import org.bibletranslationtools.shared.resources.value_source_edition
import org.bibletranslationtools.shared.resources.value_source_edition_with_code
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** [items] as one localized list: joined pairwise with the `list_join` template, so a locale can change it. */
@Composable
fun localizedList(items: List<String>): String {
    if (items.isEmpty()) return ""
    var text = items.first()
    for (item in items.drop(1)) text = stringResource(Res.string.list_join, text, item)
    return text
}

/** A take's recording date as the locale writes it in full, e.g. "September 9, 2026". */
internal fun takeDateText(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))

/** A translation type's display name: its identifier upper-cased, e.g. "ULB". */
@Composable
internal fun typeLabel(type: String): String =
    if (type.isEmpty()) stringResource(Res.string.info_value_unknown) else type.uppercase()

/**
 * A source edition as the user sees it: identifier, version label and issue month, for example
 * "ULB 12 · Nov 2017", plus [distinguishingCode] when two installed editions would otherwise
 * look the same.
 */
@Composable
fun sourceEditionText(edition: ResourceMetadata, distinguishingCode: String? = null): String {
    val pattern = stringResource(Res.string.edition_month_pattern)
    val issued = remember(edition.issued, pattern) {
        edition.issued.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }
    val identifier = edition.identifier.uppercase()
    // Some manifests quote the version, e.g. "12.1" with the quotes.
    val version = edition.version.trim().trim('"', '\'')
    return if (distinguishingCode == null) {
        stringResource(Res.string.value_source_edition, identifier, version, issued)
    } else {
        stringResource(Res.string.value_source_edition_with_code, identifier, version, issued, distinguishingCode)
    }
}
