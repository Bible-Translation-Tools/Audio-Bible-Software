package org.wycliffeassociates.resourcecontainer.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class MediaManifest(
    // Include.NON_NULL used to drop this key when null. kaml has no explicitNulls equivalent, so
    // without this it would start emitting `resource: null` into every media.yaml.
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    var resource: Resource? = null,
    var projects: List<MediaProject> = listOf()
)

@Serializable
data class Resource(
    var version: String = "",
    var media: List<Media> = listOf()
)

@Serializable
data class MediaProject(
    var identifier: String = "",
    var version: String = "",
    var media: List<Media> = listOf()
)

@Serializable
data class Media(
    var identifier: String = "",
    var version: String = "",
    var url: String = "",
    var quality: List<String> = listOf(),
    @SerialName("chapter_url")
    var chapterUrl: String = ""
)

fun mediamanifest(init: MediaManifest.() -> Unit): MediaManifest {
    val manifest = MediaManifest()
    manifest.init()
    return manifest
}

fun resource(init: Resource.() -> Unit): Resource {
    val resource = Resource()
    resource.init()
    return resource
}

fun mediaproject(init: MediaProject.() -> Unit): MediaProject {
    val project = MediaProject()
    project.init()
    return project
}

fun media(init: Media.() -> Unit): Media {
    val media = Media()
    media.init()
    return media
}