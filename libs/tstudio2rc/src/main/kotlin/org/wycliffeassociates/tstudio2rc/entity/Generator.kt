package org.wycliffeassociates.tstudio2rc.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Generator(
    val name: String,
    val build: String
)