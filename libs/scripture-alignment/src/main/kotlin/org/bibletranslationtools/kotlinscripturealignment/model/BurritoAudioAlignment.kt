package org.bibletranslationtools.kotlinscripturealignment.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import org.bibletranslationtools.kotlinscripturealignment.serializers.BurritoAudioAlignmentSerializer
import org.bibletranslationtools.vtt.WebVttDocument
import java.io.File

/**
 * The alignment-document codec, replacing the per-call ObjectMapper + SimpleModule wiring.
 *
 * `ignoreUnknownKeys` is FAIL_ON_UNKNOWN_PROPERTIES=false. The old writer also set NON_NULL
 * inclusion and WRITE_NULL_MAP_VALUES=false, but those never applied to the document itself —
 * every class here is written by a hand-rolled serializer that decides its own keys, and those
 * rules are reproduced inside BurritoAudioAlignmentSerializer, GroupSerializer and
 * RecordSerializer. ORDER_MAP_ENTRIES_BY_KEYS only affected `documents` maps, whose insertion
 * order is preserved instead.
 */
private val ALIGNMENT_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // NOT prettyPrint: ObjectMapper.writeValue is compact by default, and the alignment tests
    // assert on exact `"key":"value"` substrings.
}

@Serializable(with = BurritoAudioAlignmentSerializer::class)
data class BurritoAudioAlignment(
    var format: FormatType = FormatType.ALIGNMENT,
    var version: String = "0.3",
    var type: String,
    var documents: Documents? = null,
    var roles: List<String>? = null,
    var records: List<Record> = listOf(),
    var groups: List<Group>? = null
) {

    @Transient
    var alignmentFile: File? = null

    fun audioFileName(): String {
        if (groups != null) {
            for (group in groups!!) {
                val currentGroupDocuments = group.documents
                when (currentGroupDocuments) {
                    is DocumentsList -> {
                        val timecodeDoc = currentGroupDocuments.list.firstOrNull { it.scheme == "vtt-timecode" }
                        if (timecodeDoc?.docid != null) return timecodeDoc.docid
                    }
                    is DocumentsMap -> {
                        val timecodeDoc = currentGroupDocuments.map["timecode"]
                        if (timecodeDoc?.docid != null) return timecodeDoc.docid
                    }
                    else -> { /* Handle null or other Documents types if necessary */} // Added else branch
                }
            }
        } else if (documents != null) {
            val currentDocuments = documents
            when (currentDocuments) {
                is DocumentsList -> {
                    val timecodeDoc = currentDocuments.list.firstOrNull { it.scheme == "vtt-timecode" }
                    if (timecodeDoc?.docid != null) return timecodeDoc.docid
                }
                is DocumentsMap -> {
                    val timecodeDoc = currentDocuments.map["timecode"]
                    if (timecodeDoc?.docid != null) return timecodeDoc.docid
                }
                else -> { /* Handle null or other Documents types if necessary */} // Added else branch
            }
        }
        return ""
    }

    fun getAllDocids(): List<String> {
        val docids = mutableSetOf<String>()

        if (!groups.isNullOrEmpty()) {
            groups!!.forEach { group ->
                val groupDocs = group.documents
                when (groupDocs) {
                    is DocumentsList -> groupDocs.list.forEach { if (it.docid != null) docids.add(it.docid) }
                    is DocumentsMap -> groupDocs.map.forEach { (key, docRef) -> if (docRef.docid != null) docids.add(docRef.docid) }
                    else -> {}
                }
            }
        } else if (documents != null) {
            when (documents) {
                is DocumentsList -> (documents as DocumentsList).list.forEach { if (it.docid != null) docids.add(it.docid) }
                is DocumentsMap -> (documents as DocumentsMap).map.forEach { (key, docRef) -> if (docRef.docid != null) docids.add(docRef.docid) }
                else -> {}
            }
        }
        return docids.toList()
    }

        fun getVttCues(docid: String): List<WebVttDocument.WebVttCueContent> {
        val targetDocuments: Documents?
        val targetRecords: List<Record>
        val targetRoles: List<String>?

        if (!groups.isNullOrEmpty()) {
            val targetGroup = groups!!.firstOrNull { group ->
                val groupDocs = group.documents
                val foundInGroup = when (groupDocs) {
                    is DocumentsList -> groupDocs.list.any { it.scheme == "vtt-timecode" && ( it.docid == docid || File(it.docid).name == docid) }
                    is DocumentsMap -> groupDocs.map.any { (key, docRef) -> docRef.scheme == "vtt-timecode" && docRef.docid == docid }
                    else -> false
                }
                foundInGroup
            }
            if (targetGroup != null) {
                targetDocuments = targetGroup.documents
                targetRecords = targetGroup.records
                targetRoles = this.roles // Roles are typically hoisted to top-level for all groups
            } else {
                throw IllegalArgumentException("No group found with vtt-timecode document for docid: $docid")
            }
        } else {
            // Use top-level documents and records (implicit single group)
            val topLevelDocs = this.documents
            val docidFound: Boolean = when (topLevelDocs) {
                is DocumentsList -> topLevelDocs.list.any { it.scheme == "vtt-timecode" && ( it.docid == docid || File(it.docid).name == docid) }
                is DocumentsMap -> topLevelDocs.map.any { (key, docRef) -> docRef.scheme == "vtt-timecode" && docRef.docid == docid }
                else -> false
            }

            if (docidFound) {
                targetDocuments = topLevelDocs
                targetRecords = this.records
                targetRoles = this.roles
            } else {
                throw IllegalArgumentException("No top-level vtt-timecode document found for docid: $docid")
            }
        }

        val cues = targetRecords.map { record ->
            record.toWebVttCueContent(targetRoles)!!
        }.toMutableList()

        cues.sortWith { first, second ->
            val startIsSame = first.cue.startTimeUs == second.cue.startTimeUs
            val endIsGreater = first.cue.endTimeUs > second.cue.endTimeUs
            when {
                startIsSame && endIsGreater -> -1 // the greater end should come first
                startIsSame && !endIsGreater -> 1
                else -> first.cue.startTimeUs.compareTo(second.cue.startTimeUs)
            }
        }
        return cues
    }

    fun write(outFile: File) {
        outFile.writeText(ALIGNMENT_JSON.encodeToString(serializer(), this))
    }

    fun update() {
        alignmentFile?.let {
            write(it)
        }
    }

    fun setRecordsFromVttCueContent(docid: String, content: List<WebVttDocument.WebVttCueContent>) {
        val newRecords = content.map { vttCueContent ->
            val cueText = listOf("${Companion.timestamp(vttCueContent.cue.startTimeUs)} --> ${Companion.timestamp(vttCueContent.cue.endTimeUs)}")
            val textRef = listOf(vttCueContent.tag)
            Record(timecode = cueText, textReference = textRef)
        }

        if (!groups.isNullOrEmpty()) {
            // Update records of the target group
            val updatedGroups = groups!!.map { group ->
                val groupDocs = group.documents
                val targetGroupFound: Boolean = when (groupDocs) {
                    is DocumentsList -> groupDocs.list.any { it.scheme == "vtt-timecode" && it.docid == docid }
                    is DocumentsMap -> groupDocs.map.any { (key, docRef) -> docRef.scheme == "vtt-timecode" && docRef.docid == docid }
                    else -> false
                }

                if (targetGroupFound) {
                    group.copy(records = newRecords)
                } else {
                    group
                }
            }
            groups = updatedGroups
        } else {
            // Update top-level records
            val topLevelDocs = this.documents
            val docidFound: Boolean = when (topLevelDocs) {
                is DocumentsList -> topLevelDocs.list.any { it.scheme == "vtt-timecode" && it.docid == docid }
                is DocumentsMap -> topLevelDocs.map.any { (key, docRef) -> docRef.scheme == "vtt-timecode" && docRef.docid == docid }
                else -> false
            }
            if (docidFound) {
                records = newRecords
            } else {
                throw IllegalArgumentException("No top-level vtt-timecode document found for docid: $docid for setting records.")
            }
        }
    }

    companion object {

        fun create(audioFile: File, timingFile: File): BurritoAudioAlignment {
            if (!timingFile.exists()) {
                timingFile.createNewFile()
            } else {
                timingFile.delete()
                timingFile.createNewFile()
            }

            val groupDocuments = DocumentsList(
                listOf(
                    DocumentReference("vtt-timecode", audioFile.name),
                    DocumentReference("u23003", null)
                )
            )

            val group = Group(documents = groupDocuments, records = listOf())

            val alignment = BurritoAudioAlignment(
                FormatType.ALIGNMENT,
                "0.3",
                "audio-reference",
                null, // documents is null as it's now in groups
                listOf("timecode", "text-reference"),
                listOf(), // records is null as it's now in groups
                listOf(group)
            )

            timingFile.writeText(ALIGNMENT_JSON.encodeToString(serializer(), alignment))
            alignment.alignmentFile = timingFile // Assign the file to the returned alignment
            return alignment
        }

        private fun timestamp(timeUs: Long): String {
            val hours = timeUs / 3_600_000_000L
            val minutes = (timeUs % 3_600_000_000L) / 60_000_000L
            val seconds = (timeUs % 60_000_000L) / 1_000_000L
            val milliseconds = (timeUs % 1_000_000L) / 1000L

            return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
        }

        fun load(timingFile: File): BurritoAudioAlignment {
            val timing = ALIGNMENT_JSON.decodeFromString(serializer(), timingFile.readText())
            timing.alignmentFile = timingFile
            return timing
        }

        fun load(timing: String): BurritoAudioAlignment =
            ALIGNMENT_JSON.decodeFromString(serializer(), timing)
    }
}
