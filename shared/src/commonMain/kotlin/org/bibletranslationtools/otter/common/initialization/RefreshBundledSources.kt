package org.bibletranslationtools.otter.common.initialization

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.rxCompletable
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.api.persistence.config.Initializable
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.project.BundledSourceStamps
import org.bibletranslationtools.otter.common.domain.project.GlSourceCatalog
import org.bibletranslationtools.otter.common.domain.project.ImportProjectUseCase
import org.bibletranslationtools.otter.common.domain.project.SOURCE_PATH_TEMPLATE
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.InstalledSourceEditions
import org.slf4j.LoggerFactory

/**
 * Imports a bundled source again when this build bundles a different zip for it than the one last
 * imported, so a newer edition shipped with an app update reaches devices that already have the
 * source. It goes through the ordinary import: the same edition only has its media merged, and a
 * new edition is installed beside the old one, which is then removed if nothing uses it (see
 * EditionLifecycle).
 *
 * Only sources with an edition already installed are refreshed; the others are imported on demand
 * when a project first needs them. [BundledSourceStamps] records which zip was last imported, so
 * an unchanged bundle costs nothing. Never fails initialization: a source that can't be refreshed
 * is logged and tried again next launch.
 *
 * Runs after [BackfillEditionFingerprints], which the same-edition check depends on.
 */
class RefreshBundledSources(
    private val catalog: GlSourceCatalog,
    private val installedEditions: InstalledSourceEditions,
    private val importUseCase: ImportProjectUseCase,
    private val stamps: BundledSourceStamps,
    private val bundledContent: IBundledContentSource,
    private val tempFiles: ITempFileProvider
) : Initializable {

    private val logger = LoggerFactory.getLogger(RefreshBundledSources::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable = rxCompletable(Dispatchers.IO) {
        for (name in catalog.embeddedSourceChecksums.keys) {
            if (stamps.isCurrent(name)) continue
            val languageCode = catalog.sources.firstOrNull { it.name == name }?.languageCode ?: continue
            val identifier = name.removePrefix("${languageCode}_")
            if (installedEditions.editionsOf(languageCode, identifier).isEmpty()) continue

            runCatching { refresh(name) }
                .onSuccess { result ->
                    if (result == ImportResult.SUCCESS || result == ImportResult.ALREADY_EXISTS) {
                        stamps.markImported(name)
                        logger.info("Refreshed bundled source $name: $result")
                    } else {
                        logger.error("Refreshing bundled source $name failed: $result")
                    }
                }
                .onFailure { logger.error("Refreshing bundled source $name failed", it) }
        }
    }

    private suspend fun refresh(name: String): ImportResult {
        val zip = tempFiles.createTempFile(name, ".zip")
        return try {
            zip.writeBytes(bundledContent.read(SOURCE_PATH_TEMPLATE.format(name)))
            importUseCase.import(zip, callback = null).await()
        } finally {
            zip.delete()
        }
    }
}
