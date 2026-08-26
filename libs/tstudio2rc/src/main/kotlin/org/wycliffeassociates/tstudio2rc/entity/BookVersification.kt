package org.wycliffeassociates.tstudio2rc.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class BookVersification(
    @SerialName("en_name")
    val enName: String = "",
    val chapters: Int? = null,
    val verses: List<Int> = listOf(),
    @SerialName("usfm_number")
    val usfmNumber: String = "",
    val sort: Int
)