package org.bibletranslationtools.orature.ui.components

import org.bibletranslationtools.orature.resources.editionMonthPattern
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.sourceEdition
import org.bibletranslationtools.orature.resources.sourceEditionWithCode
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.jetbrains.compose.resources.stringResource
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A source edition as the user sees it: identifier, version label and issue month, for example
 * "ULB 12 · Nov 2017", plus [distinguishingCode] when two installed editions would otherwise look
 * the same.
 */
@Composable
fun sourceEditionText(edition: ResourceMetadata, distinguishingCode: String? = null): String {
    val pattern = stringResource(Res.string.editionMonthPattern)
    val issued = remember(edition.issued, pattern) {
        edition.issued.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }
    val identifier = edition.identifier.uppercase()
    // Some manifests quote the version, e.g. "12.1" with the quotes.
    val version = edition.version.trim().trim('"', '\'')
    return if (distinguishingCode == null) {
        stringResource(Res.string.sourceEdition, identifier, version, issued)
    } else {
        stringResource(Res.string.sourceEditionWithCode, identifier, version, issued, distinguishingCode)
    }
}
