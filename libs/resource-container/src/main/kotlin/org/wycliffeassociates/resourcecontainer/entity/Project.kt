package org.wycliffeassociates.resourcecontainer.entity

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.wycliffeassociates.resourcecontainer.Config

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class Project(
    // @EncodeDefault(NEVER) on every property reproduces the class's old
    // @JsonInclude(NON_EMPTY): a field still holding its default — "" , 0, or an empty list — is
    // left out of the written manifest. Without these, the Yaml instance's encodeDefaults = true
    // (which is what preserves Jackson's NON_NULL elsewhere) would start writing them.
    @EncodeDefault(EncodeDefault.Mode.NEVER) var title: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) var versification: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) var identifier: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) var sort: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) var path: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) var categories: List<String> = arrayListOf(),
    // Not serialized. Config is a consumer-supplied interface with no concrete type to decode
    // into, it is written to its own config.yaml through ResourceContainer.writeConfig(), and
    // nothing in this build ever assigns it — so under Jackson's NON_NULL it never appeared in a
    // manifest either. @Transient keeps it that way rather than asking kotlinx to serialize an
    // interface it cannot resolve.
    @Transient var config: Config? = null
)

fun project(init: Project.() -> Unit): Project {
    val project = Project()
    project.init()
    return project
}
