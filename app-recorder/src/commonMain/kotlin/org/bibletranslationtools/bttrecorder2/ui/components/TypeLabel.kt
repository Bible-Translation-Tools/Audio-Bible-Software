package org.bibletranslationtools.bttrecorder2.ui.components

import androidx.compose.runtime.Composable
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.info_value_unknown
import org.jetbrains.compose.resources.stringResource

/** A translation type's display name: its identifier upper-cased, e.g. "ULB". */
@Composable
internal fun typeLabel(type: String): String =
    if (type.isEmpty()) stringResource(Res.string.info_value_unknown) else type.uppercase()
