package org.wycliffeassociates.tstudio2rc.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class ProjectManifest(
    @SerialName("package_version")
    val packageVersion: Int,
    val format: String,
    val generator: Generator,
    @SerialName("target_language")
    val targetLanguage: TargetLanguage,
    val project: Project,
    val type: Type,
    val resource: Resource,
    @SerialName("source_translations")
    val sourceTranslations: List<SourceTranslation>,
    val translators: List<String>,
    @SerialName("finished_chunks")
    val finishedChunks: List<String>
)