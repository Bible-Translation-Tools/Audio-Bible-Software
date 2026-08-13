package org.wycliffeassociates.tstudio2rc.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class Resource(
    val id: String,
    val name: String = ""
)