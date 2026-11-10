package org.bibletranslationtools.otter.common.domain.project.exporter

import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterExportRulesTest {

    private val empty = ChapterStatus(anyAudioRecorded = false, audioRecorded = false, verseMarkersPlaced = true)
    private val partial = ChapterStatus(anyAudioRecorded = true, audioRecorded = false, verseMarkersPlaced = true)
    private val unmarked = ChapterStatus(anyAudioRecorded = true, audioRecorded = true, verseMarkersPlaced = false)
    private val done = ChapterStatus(anyAudioRecorded = true, audioRecorded = true, verseMarkersPlaced = true)

    private fun exportable(type: ExportType) =
        listOf(empty, partial, unmarked, done).map { ExportRules.forType(type).canExport(it) }

    @Test
    fun `backup takes any chapter with some audio`() {
        assertEquals(listOf(false, true, true, true), exportable(ExportType.BACKUP))
    }

    @Test
    fun `source audio and publish take only complete chapters with markers`() {
        assertEquals(listOf(false, false, false, true), exportable(ExportType.SOURCE_AUDIO))
        assertEquals(listOf(false, false, false, true), exportable(ExportType.PUBLISH))
    }

    @Test
    fun `listen takes complete chapters with or without markers`() {
        assertEquals(listOf(false, false, true, true), exportable(ExportType.LISTEN))
    }
}
