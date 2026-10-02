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
    /** 2 added [SourceStructureReport.projects]; installs that wrote version 1 write it again. */
    override val version = 2

    private val logger = LoggerFactory.getLogger(AuditSourceStructure::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        return Completable.fromAction {
            if (installedEntityRepo.getInstalledVersion(this) == version) return@fromAction
            try {
                val audited = auditById()
                writeReport(audited.values.toList(), auditProjects(audited))
                installedEntityRepo.install(this)
            } catch (e: Exception) {
                logger.error("Source structure audit failed; it will run again next launch", e)
            }
        }
    }

    /** Every installed source whose text could be read. */
    fun audit(): List<SourceStructureFindings> = auditById().values.toList()

    /**
     * Project books with verse rows that came from a source's empty verses after a chapter's text
     * (like English ULB Acts 19:41 imported between 31 July and the import fix), whether recorded or
     * not. Reported only (S13-Q2): an upgrade can remove the unrecorded ones.
     */
    fun auditProjects(): List<ProjectPhantomVerses> = auditProjects(auditById())

    private fun auditProjects(sources: Map<Int, SourceStructureFindings>): List<ProjectPhantomVerses> {
        val phantomsBySource = sources.mapValues { it.value.emptyAfterTextEnd.toSet() }.filterValues { it.isNotEmpty() }
        if (phantomsBySource.isEmpty()) return emptyList()
        val metadata = daoProvider.resourceMetadataDao.fetchAll().associateBy { it.id }
        val collectionDao = daoProvider.collectionDao
        val textType = daoProvider.contentTypeDao.fetchId(ContentType.TEXT)
        return collectionDao.fetchAll()
            .filter { it.parentFk == null }
            .mapNotNull { book ->
                val derived = metadata[book.dublinCoreFk] ?: return@mapNotNull null
                val sourceId = derived.derivedFromFk ?: return@mapNotNull null
                val phantom = mutableListOf<String>()
                val recorded = mutableListOf<String>()
                collectionDao.fetchChildren(book).forEach { chapter ->
                    val structure = collectionDao.fetchStructureEdition(chapter.id) ?: sourceId
                    val empty = phantomsBySource[structure] ?: return@forEach
                    daoProvider.contentDao.fetchByCollectionId(chapter.id)
                        .filter { it.type_fk == textType && it.labelKey == "verse" && "${chapter.slug}:${it.start}" in empty }
                        .forEach { row ->
                            val verse = "${chapter.slug}:${row.start}"
                            phantom += verse
                            if (daoProvider.takeDao.fetchByContentId(row.id, includeDeleted = false).isNotEmpty()) recorded += verse
                        }
                }
                if (phantom.isEmpty()) return@mapNotNull null
                val source = metadata.getValue(sourceId)
                ProjectPhantomVerses(
                    book = book.slug,
                    targetLanguage = daoProvider.languageDao.fetchById(derived.languageFk)?.slug.orEmpty(),
                    sourceLanguage = daoProvider.languageDao.fetchById(source.languageFk)?.slug.orEmpty(),
                    sourceIdentifier = source.identifier,
                    sourceVersion = source.version,
                    phantomVerses = phantom,
                    recordedVerses = recorded
                )
            }
    }

    private fun auditById(): Map<Int, SourceStructureFindings> {
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
                source.id to auditSourceStructure(
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
        }.toMap()
    }

    private fun readText(source: ResourceMetadataEntity): Map<String, Set<Int>> =
        ResourceContainer.load(File(source.path), OtterResourceContainerConfig()).use { rc ->
            textVersesByChapter(IProjectReader.constructContainerTree(rc, zipEntryTreeBuilder))
        }

    private fun writeReport(findings: List<SourceStructureFindings>, projects: List<ProjectPhantomVerses>) {
        findings.forEach {
            logger.info(
                "Source structure ${it.language}_${it.identifier} v${it.version}: " +
                    "${it.droppedVerses.size} verse(s) of text dropped, " +
                    "${it.emptyAfterTextEnd.size} empty verse(s) after a chapter's text, " +
                    "${it.emptyInsideChapter.size} empty verse(s) inside a chapter, " +
                    "${it.templateChapters} template chapter(s)"
            )
        }
        projects.forEach {
            logger.info(
                "Project ${it.targetLanguage}/${it.book} from ${it.sourceLanguage}_${it.sourceIdentifier} v${it.sourceVersion}: " +
                    "${it.phantomVerses.size} verse(s) with no source text, ${it.recordedVerses.size} of them recorded"
            )
        }
        val report = SourceStructureReport(
            reportVersion = version,
            sourcesNeedingRepair = findings.count { it.needsRepair },
            sources = findings,
            projects = projects
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
    val sources: List<SourceStructureFindings>,
    /** Project books holding verses their source has no text for; see [AuditSourceStructure.auditProjects]. */
    val projects: List<ProjectPhantomVerses> = emptyList()
)

/**
 * A project book's verse rows copied from its source's empty verses after a chapter's text, as
 * `<chapter slug>:<verse>`, and which of them are recorded.
 */
@Serializable
data class ProjectPhantomVerses(
    val book: String,
    val targetLanguage: String,
    val sourceLanguage: String,
    val sourceIdentifier: String,
    val sourceVersion: String,
    val phantomVerses: List<String>,
    val recordedVerses: List<String>
)

private val reportJson = Json { prettyPrint = true }
