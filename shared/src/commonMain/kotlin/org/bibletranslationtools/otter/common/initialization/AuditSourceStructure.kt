package org.bibletranslationtools.otter.common.initialization

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.resourcecontainer.OtterResourceContainerConfig
import org.bibletranslationtools.otter.common.domain.resourcecontainer.SourceStructureFindings
import org.bibletranslationtools.otter.common.domain.resourcecontainer.StoredVerseRow
import org.bibletranslationtools.otter.common.domain.resourcecontainer.auditSourceStructure
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IProjectReader
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.IZipEntryTreeBuilder
import org.bibletranslationtools.otter.common.domain.resourcecontainer.textVersesByChapter
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.slf4j.LoggerFactory
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File

const val SOURCE_STRUCTURE_REPORT_FILE = "source-structure-report.json"

/**
 * Writes, once, a report comparing every installed source's stored verse rows with its own text.
 *
 * Imports since 31 July 2026 built verse rows from a versification file instead of the text, which
 * dropped text (Russian, Thai) and added verses with no text (English Acts 19:41). Import is fixed;
 * already-imported sources are deliberately not repaired. This report records which sources would
 * need a repair, so that can be decided later. It is written to [IAppDirectories.logsDirectory] as
 * [SOURCE_STRUCTURE_REPORT_FILE], and summarised in the log.
 *
 * It never fails initialization: an error is logged and the audit tries again next launch.
 */
class AuditSourceStructure(
    private val daoProvider: DaoProvider,
    private val directories: IAppDirectories,
    private val installedEntityRepo: IInstalledEntityRepository,
    private val zipEntryTreeBuilder: IZipEntryTreeBuilder
) : Installable {

    override val name = "SOURCE_STRUCTURE_AUDIT"
    override val version = 1

    private val logger = LoggerFactory.getLogger(AuditSourceStructure::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        return Completable.fromAction {
            if (installedEntityRepo.getInstalledVersion(this) == version) return@fromAction
            try {
                val findings = audit()
                writeReport(findings)
                installedEntityRepo.install(this)
            } catch (e: Exception) {
                logger.error("Source structure audit failed; it will run again next launch", e)
            }
        }
    }

    /** Every installed source whose text could be read. */
    fun audit(): List<SourceStructureFindings> {
        val sources = daoProvider.resourceMetadataDao.fetchAll().filter { it.derivedFromFk == null }
        val collections = daoProvider.collectionDao.fetchAll()
        val textType = daoProvider.contentTypeDao.fetchId(ContentType.TEXT)
        return sources.mapNotNull { source ->
            runCatching {
                val storedRows = collections
                    .filter { it.dublinCoreFk == source.id }
                    .associate { chapter ->
                        chapter.slug to daoProvider.contentDao.fetchByCollectionId(chapter.id)
                            .filter { it.type_fk == textType }
                            .map { StoredVerseRow(it.start, it.end) }
                    }
                    .filterValues { it.isNotEmpty() }
                auditSourceStructure(
                    identifier = source.identifier,
                    language = daoProvider.languageDao.fetchById(source.languageFk)?.slug.orEmpty(),
                    version = source.version,
                    path = source.path,
                    textVerses = readText(source),
                    storedRows = storedRows
                )
            }.onFailure {
                logger.error("Could not audit source ${source.identifier} at ${source.path}", it)
            }.getOrNull()
        }
    }

    private fun readText(source: ResourceMetadataEntity): Map<String, Set<Int>> =
        ResourceContainer.load(File(source.path), OtterResourceContainerConfig()).use { rc ->
            textVersesByChapter(IProjectReader.constructContainerTree(rc, zipEntryTreeBuilder))
        }

    private fun writeReport(findings: List<SourceStructureFindings>) {
        findings.forEach {
            logger.info(
                "Source structure ${it.language}_${it.identifier} v${it.version}: " +
                    "${it.droppedVerses.size} verse(s) of text dropped, " +
                    "${it.emptyAfterTextEnd.size} empty verse(s) after a chapter's text, " +
                    "${it.emptyInsideChapter.size} empty verse(s) inside a chapter, " +
                    "${it.templateChapters} template chapter(s)"
            )
        }
        val report = SourceStructureReport(
            reportVersion = version,
            sourcesNeedingRepair = findings.count { it.needsRepair },
            sources = findings
        )
        directories.logsDirectory.mkdirs()
        File(directories.logsDirectory, SOURCE_STRUCTURE_REPORT_FILE)
            .writeText(reportJson.encodeToString(SourceStructureReport.serializer(), report))
    }
}

@Serializable
data class SourceStructureReport(
    val reportVersion: Int,
    val sourcesNeedingRepair: Int,
    val sources: List<SourceStructureFindings>
)

private val reportJson = Json { prettyPrint = true }
