package org.bibletranslationtools.otter.common.initialization

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import kotlinx.coroutines.rx2.rxCompletable
import org.bibletranslationtools.otter.common.api.persistence.config.Initializable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IEditionFingerprintRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus
import org.bibletranslationtools.otter.common.domain.project.importer.EditionFingerprinter
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Fingerprints every installed source that has no fingerprint yet: sources imported before schema
 * v15 added them. Runs each launch, and does nothing once every source has one.
 *
 * A source that can't be read is logged and skipped, so it is tried again next launch; it never
 * fails initialization.
 */
class BackfillEditionFingerprints(
    private val daoProvider: DaoProvider,
    private val fingerprintRepository: IEditionFingerprintRepository,
    private val fingerprinter: EditionFingerprinter
) : Initializable {

    private val logger = LoggerFactory.getLogger(BackfillEditionFingerprints::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable = rxCompletable {
        for (id in fingerprintRepository.sourcesWithoutFingerprint()) {
            val source = daoProvider.resourceMetadataDao.fetchById(id) ?: continue
            runCatching { fingerprinter.fingerprint(File(source.path)) }
                .onSuccess {
                    fingerprintRepository.save(id, it)
                    logger.info("Fingerprinted source ${source.identifier} v${source.version} (${it.detectedVersification})")
                }
                .onFailure { logger.error("Could not fingerprint source ${source.identifier} at ${source.path}", it) }
        }
    }
}
