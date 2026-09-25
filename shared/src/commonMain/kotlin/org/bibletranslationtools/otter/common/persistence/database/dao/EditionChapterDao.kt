package org.bibletranslationtools.otter.common.persistence.database.dao

import org.bibletranslationtools.otter.common.persistence.entities.EditionChapterEntity

/** The DAO contract for the `edition_chapter` table: per-chapter fingerprints of a source edition. */
interface EditionChapterDao {
    /** Replaces every chapter fingerprint of edition [dublinCoreFk] with [chapters]. */
    fun replaceForEdition(dublinCoreFk: Int, chapters: List<EditionChapterEntity>)
    fun fetchForEdition(dublinCoreFk: Int): List<EditionChapterEntity>
}
