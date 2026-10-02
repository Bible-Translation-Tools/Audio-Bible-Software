package org.bibletranslationtools.otter.common.persistence.entities

/** One row of `edition_chapter`: a source edition's fingerprint for one chapter. */
data class EditionChapterEntity(
    val dublinCoreFk: Int,
    val chapterSlug: String,
    val structureHash: String,
    val textHash: String
)

/** The edition fingerprint columns of one `dublin_core_entity` row; all null until it is set. */
data class EditionFingerprintEntity(
    val detectedVersification: String?,
    val structureFingerprint: String?,
    val textFingerprint: String?
)
