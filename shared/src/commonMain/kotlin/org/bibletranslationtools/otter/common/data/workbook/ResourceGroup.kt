package org.bibletranslationtools.otter.common.data.workbook

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

/** The resources (such as notes or questions) of one resource container for an element. */
class ResourceGroup internal constructor(
    val metadata: ResourceMetadata,
    private val load: suspend () -> List<Resource>
) {
    private val loading = Mutex()
    private var loaded: List<Resource>? = null

    /** The resources, read from storage the first time and kept. */
    suspend fun resources(): List<Resource> =
        loaded ?: loading.withLock { loaded ?: load().also { loaded = it } }
}
