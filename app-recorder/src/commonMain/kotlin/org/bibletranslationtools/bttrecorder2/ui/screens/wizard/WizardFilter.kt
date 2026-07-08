package org.bibletranslationtools.bttrecorder2.ui.screens.wizard

import java.util.Locale

/**
 * Search/filter logic ported verbatim from the original BTT-Recorder
 * `TargetLanguageAdapter` and `GenericAdapter` `Filter` implementations
 * (see `composeApp/old/translationRecorder/.../project/adapters/`).
 *
 * Match rules (both adapters use the same shape):
 *   - Empty query → return the full list, original order.
 *   - Lowercased prefix match against `slug` OR `name` — items whose slug or
 *     name **starts with** the query (case-insensitive) are kept.
 *
 * Sort rule (`sortedLanguages` / `sortProjectComponents`):
 *   - Sort kept items by `slug` ascending, but prepend `'!'` to any slug
 *     that starts with the query. Since `'!'` sorts before all alphanumeric
 *     characters, slug-prefix matches float to the top.
 *
 * This is a `startsWith` filter — substring matches are intentionally not
 * supported, so typing "alg" surfaces "Algerian…" but not "Bulgarian".
 */
internal fun <T> filterAndSortStartsWith(
    items: List<T>,
    query: String,
    slugOf: (T) -> String,
    nameOf: (T) -> String
): List<T> {
    if (query.isEmpty()) return items

    val q = query.lowercase(Locale.getDefault())

    val matches = items.filter { item ->
        val slug = slugOf(item).lowercase(Locale.getDefault())
        val name = nameOf(item).lowercase(Locale.getDefault())
        slug.startsWith(q) || name.startsWith(q)
    }

    return matches.sortedWith(Comparator { lhs, rhs ->
        var lhId = slugOf(lhs)
        var rhId = slugOf(rhs)
        // Same '!' priority trick the original uses: slug-prefix matches
        // sort before non-slug-prefix matches.
        if (lhId.lowercase(Locale.getDefault()).startsWith(q)) lhId = "!$lhId"
        if (rhId.lowercase(Locale.getDefault()).startsWith(q)) rhId = "!$rhId"
        lhId.compareTo(rhId, ignoreCase = true)
    })
}
