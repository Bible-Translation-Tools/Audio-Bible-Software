package org.bibletranslationtools.otter.common.domain.project

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import org.bibletranslationtools.otter.common.api.persistence.config.Installable
import org.bibletranslationtools.otter.common.api.persistence.repositories.IInstalledEntityRepository
import org.bibletranslationtools.otter.common.data.ProgressStatus

/**
 * Which build of each bundled source zip was last imported, so a bundled source is only imported
 * again when an app update ships a different zip for it.
 *
 * Kept as installed entities named `BUNDLED_SOURCE:<name>`, versioned from the zip's checksum in
 * files/embedded_gl_source_checksums.json; only whether it changed matters, not its value.
 */
class BundledSourceStamps(
    private val catalog: GlSourceCatalog,
    private val installedEntityRepo: IInstalledEntityRepository
) {
    /** Whether bundled source [name]'s current zip is the one last imported. */
    fun isCurrent(name: String): Boolean {
        val stamp = stampFor(name) ?: return false
        return installedEntityRepo.getInstalledVersion(stamp) == stamp.version
    }

    /** Records that bundled source [name]'s current zip has been imported. */
    fun markImported(name: String) {
        stampFor(name)?.let(installedEntityRepo::install)
    }

    private fun stampFor(name: String): Installable? {
        val checksum = catalog.embeddedSourceChecksums[name] ?: return null
        return object : Installable {
            override val name = "BUNDLED_SOURCE:$name"
            override val version = checksum.take(7).toInt(16)
            override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable = Completable.complete()
        }
    }
}
