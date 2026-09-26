package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.value_source_edition
import org.bibletranslationtools.shared.resources.value_source_edition_with_code
import org.jetbrains.compose.resources.stringResource
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A source edition as the user sees it: identifier, version label and issue month, for example
 * "ULB 12 · Nov 2017", plus [distinguishingCode] when two installed editions would otherwise
 * look the same.
 */
@Composable
fun sourceEditionText(edition: ResourceMetadata, distinguishingCode: String? = null): String {
    val issued = remember(edition.issued) {
        edition.issued.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()))
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
