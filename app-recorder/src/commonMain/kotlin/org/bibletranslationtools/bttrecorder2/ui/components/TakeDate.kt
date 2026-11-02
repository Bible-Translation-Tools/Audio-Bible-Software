package org.bibletranslationtools.bttrecorder2.ui.components

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A take's recording date as the locale writes it in full, e.g. "September 9, 2026". */
internal fun takeDateText(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
