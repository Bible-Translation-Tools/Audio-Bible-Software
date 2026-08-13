package org.wycliffeassociates.resourcecontainer.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
class Manifest(
    @SerialName("dublin_core")
    var dublinCore: DublinCore,
    var projects: List<Project>,
    var checking: Checking
)

fun manifest(init: Manifest.() -> Unit): Manifest {
        val manifest = Manifest(DublinCore(), arrayListOf(Project()), Checking())
        manifest.init()
        return manifest
}