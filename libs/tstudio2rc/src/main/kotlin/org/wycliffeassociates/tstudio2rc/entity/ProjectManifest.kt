package org.wycliffeassociates.tstudio2rc.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown=true)
data class ProjectManifest(
    @JsonProperty("package_version")
    val packageVersion: Int,
    val format: String,
    val generator: Generator,
    @JsonProperty("target_language")
    val targetLanguage: TargetLanguage,
    val project: Project,
    val type: Type,
    val resource: Resource,
    @JsonProperty("source_translations")
    val sourceTranslations: List<SourceTranslation>,
    val translators: List<String>,
    @JsonProperty("finished_chunks")
    val finishedChunks: List<String>
)