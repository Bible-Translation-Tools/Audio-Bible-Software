package org.wycliffeassociates.tstudio2rc.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class TargetLanguage(
    val id: String,
    val name: String,
    val direction: String
)
