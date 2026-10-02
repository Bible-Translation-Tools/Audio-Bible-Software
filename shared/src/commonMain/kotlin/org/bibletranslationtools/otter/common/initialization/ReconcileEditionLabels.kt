package org.bibletranslationtools.otter.common.initialization

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.editionFolderName
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.mapper.mapToMetadata
import org.bibletranslationtools.otter.common.domain.resourcecontainer.OtterResourceContainerConfig
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.LanguageMapper
import org.slf4j.LoggerFactory
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import java.io.File

const val EDITION_LABEL_REPORT_FILE = "edition-label-report.json"

/**
 * How an installed source's stored label compared with the manifest on disk, when they disagreed.
 *
 * @property folderVersion the label in the edition's folder name (`v<label>-<code>`), or null for a
 *   folder named before editions were kept apart (such as `en_ulb`), which says nothing about it.
 * @property relabelled the stored label and dates were set to the manifest's.
 */
@Serializable
data class EditionLabelFinding(
    val language: String,
    val identifier: String,
    val creator: String,
    val path: String,
    val storedVersion: String,
    val manifestVersion: String,
    val folderVersion: String?,
    val storedIssued: String,
    val manifestIssued: String,
    val storedModified: String,
    val manifestModified: String,
    val relabelled: Boolean
)

@Serializable
data class EditionLabelReport(val reportVersion: Int, val findings: List<EditionLabelFinding>)

/**
 * Finds sources that were merged in place before editions were kept side by side, and gives them
 * the label their content has (S13-Q1).
 *
 * Importing a newer edition used to merge it into the installed source: its rows and files were
 * updated, but the stored version, the manifest on disk and the folder name could end up
 * disagreeing. The source's content as it stands is treated as the edition, and its version label and
 * dates are taken from the manifest on disk, which describes that content. Its fingerprint already
 * comes from that content ([BackfillEditionFingerprints]). A folder name that disagrees is recorded
 * and left alone: the folder is only a place, and its path is stored.
 *
 * Runs once, before bundled sources are refreshed, so that refresh compares against the corrected
 * label. What it finds is logged and written to [IAppDirectories.logsDirectory] as
 * [EDITION_LABEL_REPORT_FILE]. It never fails initialization: an error is logged and it runs again
 * next launch.
 */
class ReconcileEditionLabels(
    private val daoProvider: DaoProvider,
    private val directories: IAppDirectories,
    private val installedEntityRepo: IInstalledEntityRepository
) : Installable {

    override val name = "EDITION_LABELS"
    override val version = 1

    private val logger = LoggerFactory.getLogger(ReconcileEditionLabels::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        return Completable.fromAction {
            if (installedEntityRepo.getInstalledVersion(this) == version) return@fromAction
            try {
                val findings = reconcile()
                writeReport(findings)
                installedEntityRepo.install(this)
            } catch (e: Exception) {
                logger.error("Reconciling source edition labels failed; it will run again next launch", e)
            }
        }
    }

    /** Compares every installed source with its manifest on disk, relabelling those that disagree. */
    fun reconcile(): List<EditionLabelFinding> {
        val dao = daoProvider.resourceMetadataDao
        return dao.fetchAll().filter { it.derivedFromFk == null }.mapNotNull { source ->
            runCatching {
                val languageEntity = daoProvider.languageDao.fetchById(source.languageFk) ?: return@runCatching null
                val language = LanguageMapper().mapFromEntity(languageEntity)
                val manifest = ResourceContainer.load(File(source.path), OtterResourceContainerConfig())
                    .use { it.manifest.dublinCore.mapToMetadata(File(source.path), language) }
                val manifestIssued = manifest.issued.toString()
                val manifestModified = manifest.modified.toString()
                val labelDiffers = source.version != manifest.version ||
                    source.issued != manifestIssued || source.modified != manifestModified
                val folderVersion = EDITION_FOLDER.matchEntire(File(source.path).name)?.groupValues?.get(1)
                val folderDiffers = folderVersion != null &&
                    !File(source.path).name.startsWith(editionFolderName(manifest.version, ""))
                if (!labelDiffers && !folderDiffers) return@runCatching null

                if (labelDiffers) {
                    dao.update(source.copy(version = manifest.version, issued = manifestIssued, modified = manifestModified))
                    logger.warn(
                        "Source ${language.slug}_${source.identifier} at ${source.path} was merged in place: " +
                            "relabelled v${source.version} (${source.issued}) to v${manifest.version} ($manifestIssued), " +
                            "as its manifest says"
                    )
                }
                if (folderDiffers) {
                    logger.warn(
                        "Source ${language.slug}_${source.identifier} v${manifest.version} is in a folder labelled " +
                            "v$folderVersion (${source.path}); left there"
                    )
                }
                EditionLabelFinding(
                    language = language.slug,
                    identifier = source.identifier,
                    creator = source.creator,
                    path = source.path,
                    storedVersion = source.version,
                    manifestVersion = manifest.version,
                    folderVersion = folderVersion,
                    storedIssued = source.issued,
                    manifestIssued = manifestIssued,
                    storedModified = source.modified,
                    manifestModified = manifestModified,
                    relabelled = labelDiffers
                )
            }.onFailure {
                logger.error("Could not compare source ${source.identifier} at ${source.path} with its manifest", it)
            }.getOrNull()
        }
    }

    private fun writeReport(findings: List<EditionLabelFinding>) {
        directories.logsDirectory.mkdirs()
        File(directories.logsDirectory, EDITION_LABEL_REPORT_FILE)
            .writeText(reportJson.encodeToString(EditionLabelReport.serializer(), EditionLabelReport(version, findings)))
    }

    private companion object {
        /** An edition folder, `v<label>-<six-character code>`. */
        val EDITION_FOLDER = Regex("""v(.+)-[0-9a-f]{6}""")
        val reportJson = Json { prettyPrint = true }
    }
}
