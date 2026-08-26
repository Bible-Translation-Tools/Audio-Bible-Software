package org.wycliffeassociates.tstudio2rc.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class SourceTranslation(
    @SerialName("language_id")
    val languageId: String,
    @SerialName("resource_id")
    val resourceId: String,
    @SerialName("checking_level")
    val checkingLevel: String,
    @SerialName("date_modified")
    val dateModified: String,
    val version: String
)