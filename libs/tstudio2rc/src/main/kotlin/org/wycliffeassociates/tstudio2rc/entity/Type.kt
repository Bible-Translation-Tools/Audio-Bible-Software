package org.wycliffeassociates.tstudio2rc.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Type(
    val id: String,
    val name: String
)