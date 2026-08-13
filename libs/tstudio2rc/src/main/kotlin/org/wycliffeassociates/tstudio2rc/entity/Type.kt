package org.wycliffeassociates.tstudio2rc.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Type(
    val id: String,
    val name: String
)