package org.bibletranslationtools.otter.common.persistence.database.sqldelight

import org.bibletranslationtools.otter.common.persistence.database.dao.EditionChapterDao
import org.bibletranslationtools.otter.common.persistence.entities.EditionChapterEntity
import org.bibletranslationtools.otter.db.OtterDatabase

internal class SqlDelightEditionChapterDao(private val db: OtterDatabase) : EditionChapterDao {
    private val queries = db.editionChapterQueries

    override fun replaceForEdition(dublinCoreFk: Int, chapters: List<EditionChapterEntity>) {
        db.transaction {
            queries.deleteForEdition(dublinCoreFk)
            chapters.forEach {
                queries.insert(dublinCoreFk, it.chapterSlug, it.structureHash, it.textHash)
            }
        }
    }

    override fun fetchForEdition(dublinCoreFk: Int): List<EditionChapterEntity> =
        queries.fetchForEdition(dublinCoreFk).executeAsList().map {
            EditionChapterEntity(it.dublin_core_fk, it.chapter_slug, it.structure_hash, it.text_hash)
        }
}
