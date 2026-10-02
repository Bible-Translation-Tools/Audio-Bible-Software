package org.bibletranslationtools.otter.common.domain.resourcecontainer

import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata

/**
 * The order of editions of one source, newest first.
 *
 * Version labels can't be compared (`12`, `24-07`, `"12.1"`), so an edition is newer when its
 * `issued` date is later, then when its `modified` date is. Editions with the same dates are
 * siblings: neither is newer, and neither replaces the other. Among siblings, the one installed
 * first comes first, so a choice between them is stable.
 */
object EditionOrder {

    val newestFirst: Comparator<ResourceMetadata> =
        compareByDescending<ResourceMetadata> { it.issued }
            .thenByDescending { it.modified }
            .thenBy { it.id }

    /** Orders items by their edition, newest first; items with no edition go last. */
    fun <T> newestFirstBy(edition: (T) -> ResourceMetadata?): Comparator<T> =
        Comparator { a, b ->
            val ea = edition(a)
            val eb = edition(b)
            when {
                ea == null && eb == null -> 0
                ea == null -> 1
                eb == null -> -1
                else -> newestFirst.compare(ea, eb)
            }
        }

    /** The newest of [editions], or null if there are none. */
    fun newest(editions: Iterable<ResourceMetadata>): ResourceMetadata? = editions.minWithOrNull(newestFirst)

    /** Whether [a] is strictly newer than [b]; false for siblings. */
    fun isNewer(a: ResourceMetadata, b: ResourceMetadata): Boolean =
        a.issued > b.issued || (a.issued == b.issued && a.modified > b.modified)
}
