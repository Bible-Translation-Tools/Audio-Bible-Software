// Derives the recorder's two mode-specific ULB sources at build time.
//
//   shared/legacy-ulb/en_ulb_v12.zip        (template, build-only, not packed)
//   shared/legacy-chunks/legacy-chunks.json (legacy chunk definitions, build-only, not packed)
//        ├──▶ files/legacy/en_ulb_verse.zip   ulb v12-verse  "Unlocked Literal Bible - Verse Mode"
//        └──▶ files/legacy/en_ulb_chunk.zip   ulb v12-chunk  "Unlocked Literal Bible - Chunk Mode"
//
// Done at build time because the chunk merge rewrites all 66 books of USFM, which is far too slow to
// run during a first-launch migration on a phone, and deterministic, so there is nothing to gain
// from repeating it per install.
//
// The pair lands in `files/legacy/` rather than `files/content/`, which
// `generateEmbeddedSourcesManifest` enumerates and the project wizard offers for sideload. From
// `files/legacy/` they are packed but inert: only `InitializeModeSources` reads them, and only once
// migration has found legacy data.
//
// TWO CONTAINERS, because a project's units come from its source's content rows, so the chunking has
// to live in the source text.
//
// SPLIT ON THE VERSION, NOT THE IDENTIFIER, so that every migrated project reads as ULB: both carry
// `identifier: 'ulb'` and differ by `version`. The version is part of both keys the storage layer
// uses — the database key `UNIQUE (language_fk, identifier, version, creator, derivedFrom_fk)` and
// the source directory `<creator>/<lang>_<identifier>/v<version>` — so these two texts and the plain
// `ulb` occupy three distinct rows and three distinct directories. The importer chain, however,
// matches on language and identifier alone, which is why `InitializeModeSources` imports these
// through `NewSourceImporter` directly.
//
// RANGES COME FROM A VENDORED FILE, NOT `\s5` MARKERS. legacy-chunks.json is distilled from the old
// APK's `assets/chunks/{nt,ot}/<book>/chunks.json` — 66 books, 1189 chapters — and that file defined
// the legacy chunking. Across en_ulb's history (every tag plus archives from 2016-06 to 2025-10) the
// same 37 chapters never match `\s5`, the definitions being a coarsened variant that merges adjacent
// sections: `jhn 9` has one chunk 26-29 where `\s5` splits 26-27 and 28-29. A take has to keep
// matching the label of the unit it is attached to, so the definitions win. `\s5` serves only as a
// fallback for a chapter they do not cover, and the markers are stripped from the chunk-mode text so
// a leftover cannot land inside a merged verse.
//
// TEMPLATE PROVENANCE. Pinned to en_ulb commit 65fcaa15130305a31666b67f98305334805fadaa
// ("Taking bridges out", 2018-05-03), the earliest revision in which every legacy chunk is
// expressible. Earlier revisions, including the `v12` tag, bridge verses across a chunk boundary
// (`\v 17-18` in 1CH 4 against a chunk ending at 17), which no rewrite can split. That commit
// removes the last 7 such bridges while still declaring `version: 12, issued: 2017-11-29`, making it
// v12-era text the legacy chunking fits exactly. The verification below fails the build if the
// template is ever swapped for a bridged revision.

val modeSourceOutDir = file("src/commonMain/composeResources/files/legacy")
val modeSourceTemplate = file("legacy-ulb/en_ulb_v12.zip")
val legacyChunkDefinitions = file("legacy-chunks/legacy-chunks.json")

/** Chapters the vendored definitions cover; a truncated file would silently change the chunking. */
val definedChapters = 1189

/** The identifier both texts carry: a migrated project is a ULB project whichever mode it was. */
val modeSourceIdentifier = "ulb"

/**
 * One generated text.
 *
 * @param version the dublin_core version, which is what distinguishes the two — see the header
 * @param mergeChunks whether its USFM gets the chunk merge applied
 */
data class ModeSource(
    val name: String,
    val version: String,
    val title: String,
    val mergeChunks: Boolean
)

val modeSources = listOf(
    ModeSource(
        name = "en_ulb_verse",
        version = "12-verse",
        title = "Unlocked Literal Bible - Verse Mode",
        mergeChunks = false
    ),
    ModeSource(
        name = "en_ulb_chunk",
        version = "12-chunk",
        title = "Unlocked Literal Bible - Chunk Mode",
        mergeChunks = true
    )
)

// `\c N`, `\s5` and `\v N[-M]` in one pass, so their order in the file is unambiguous; a marker can
// share a line with text and with other markers. Groups: 1 = chapter, 2 = verse start, 3 = verse end.
val usfmMarkers = Regex("""\\c\s+(\d+)|\\s5(?![0-9])|\\v\s+(\d+)(?:\s*-\s*(\d+))?""")

/** `book slug -> chapter -> ordered verse ranges`, from `{"jas":{"1":"1-3,4-5"}}`. */
fun parseLegacyChunks(json: String): Map<String, Map<Int, List<IntRange>>> {
    @Suppress("UNCHECKED_CAST")
    val raw = groovy.json.JsonSlurper().parseText(json) as Map<String, Map<String, String>>
    return raw.mapValues { (_, chapters) ->
        chapters.entries.associate { (chapter, ranges) ->
            chapter.toInt() to ranges.split(",").map { range ->
                val bounds = range.split("-")
                bounds[0].toInt()..bounds[1].toInt()
            }
        }
    }
}

/** One `\v` marker's position in the book, for resolving which chunk it belongs to. */
data class VerseOccurrence(val start: Int, val chapter: Int?, val sectionIndex: Int)

/**
 * Rewrites one book's USFM so each legacy chunk becomes a single bridged verse.
 *
 * Surgical: only `\v` markers and `\s5` lines change. The first verse marker of a multi-verse chunk
 * becomes `\v <first>-<last>`, the rest are dropped so their text flows into it, and everything else
 * — `\p`, `\q`, section headings, footnotes — stays exactly where it was.
 */
fun mergeChunkVerses(usfm: String, chunks: Map<Int, List<IntRange>>): String {
    val occurrences = mutableListOf<VerseOccurrence>()
    val sectionSpans = mutableListOf<MutableList<Int>>()
    var chapter: Int? = null

    usfmMarkers.findAll(usfm).forEach { match ->
        when {
            match.groupValues[1].isNotEmpty() -> {
                // A chapter boundary always opens a new section, so a chunk cannot straddle two.
                chapter = match.groupValues[1].toInt()
                sectionSpans.add(mutableListOf())
            }

            match.groupValues[2].isNotEmpty() -> {
                val start = match.groupValues[2].toInt()
                val end = match.groupValues[3].takeIf { it.isNotBlank() }?.toInt() ?: start
                if (sectionSpans.isEmpty()) sectionSpans.add(mutableListOf())
                (start..end).forEach { sectionSpans.last().add(it) }
                occurrences.add(VerseOccurrence(start, chapter, sectionSpans.lastIndex))
            }

            // `\s5` opens a section unless the current one is still empty, since the ULB opens
            // with `\s5` immediately before `\c 1` and would otherwise leave a stray empty span.
            else -> if (sectionSpans.lastOrNull()?.isNotEmpty() != false) {
                sectionSpans.add(mutableListOf())
            }
        }
    }

    var index = 0
    val opened = mutableSetOf<String>()
    val merged = usfmMarkers.replace(usfm) { match ->
        // Only verse markers change; chapter and section markers pass through untouched.
        if (match.groupValues[2].isEmpty()) return@replace match.value

        val occurrence = occurrences.getOrNull(index++) ?: return@replace match.value
        val chapterChunks = occurrence.chapter?.let { chunks[it] }
        val defined = chapterChunks?.firstOrNull { occurrence.start in it }
        val range = when {
            defined != null -> defined

            // The chapter has definitions but this verse is in none of them, which the legacy
            // lists do for a handful of verses (1CH 4:18, 1CH 6:79, 1CH 8:18/20/21/23-25,
            // PSA 68:13) by recording a then-bridged verse under its first number only. Such a
            // verse becomes its own unit: falling through to the `\s5` span would merge it into a
            // neighbour and yield overlapping ranges, and dropping it would make it unrecordable.
            !chapterChunks.isNullOrEmpty() -> return@replace match.value

            else -> sectionSpans.getOrNull(occurrence.sectionIndex)
                ?.takeIf { it.isNotEmpty() }
                ?.let { it.min()..it.max() }
                ?: return@replace match.value
        }

        if (range.last == range.first) return@replace match.value

        // Key on the chapter as well, since the same range repeats across chapters.
        if (opened.add("${occurrence.chapter}:${range.first}-${range.last}")) {
            // Keep the marker's trailing space so the text stays separated from it.
            val trailing = match.value.substringAfter(match.groupValues[2]).takeWhile { it == ' ' }
            "\\v ${range.first}-${range.last}$trailing"
        } else {
            "" // dropped; its text now belongs to the bridged verse
        }
    }

    return merged.split("\n").filterNot { it.trim() == """\s5""" }.joinToString("\n")
}

/** Replaces a top-level `dublin_core` scalar (two-space indent), not a nested one. */
fun setDublinCoreField(manifest: String, field: String, value: String): String {
    val pattern = Regex("""(?m)^  $field: '[^']*'$""")
    require(pattern.containsMatchIn(manifest)) { "manifest has no top-level '$field'" }
    return pattern.replace(manifest, "  $field: '$value'")
}

/** `book slug -> chapter -> verse ranges` as they actually appear in a generated zip. */
fun versesByBook(zip: File): Map<String, Map<Int, List<IntRange>>> {
    val slugPattern = Regex("""\\id\s+(\w+)""")
    val out = mutableMapOf<String, MutableMap<Int, MutableList<IntRange>>>()
    java.util.zip.ZipFile(zip).use { archive ->
        archive.entries().asSequence()
            .filter { it.name.endsWith(".usfm") }
            .forEach { entry ->
                val text = archive.getInputStream(entry).use { it.readBytes() }.decodeToString()
                val slug = slugPattern.find(text)?.groupValues?.get(1)?.lowercase() ?: return@forEach
                val book = out.getOrPut(slug) { mutableMapOf() }
                var chapter: Int? = null
                usfmMarkers.findAll(text).forEach { match ->
                    when {
                        match.groupValues[1].isNotEmpty() -> {
                            chapter = match.groupValues[1].toInt()
                            book.getOrPut(chapter!!) { mutableListOf() }
                        }
                        match.groupValues[2].isNotEmpty() -> chapter?.let { current ->
                            val start = match.groupValues[2].toInt()
                            val end = match.groupValues[3].takeIf { it.isNotBlank() }?.toInt() ?: start
                            book.getOrPut(current) { mutableListOf() }.add(start..end)
                        }
                    }
                }
            }
    }
    return out
}

tasks.register("generateModeSources") {
    description = "Derives the two mode-specific ULB sources in files/legacy from the ULB template."
    group = "build"

    inputs.file(modeSourceTemplate).withPropertyName("template")
    inputs.file(legacyChunkDefinitions).withPropertyName("legacy-chunk-definitions")
    inputs.property("identifier", modeSourceIdentifier)
    inputs.property(
        "modes",
        modeSources.joinToString { "${it.name}|${it.version}|${it.title}|${it.mergeChunks}" }
    )
    modeSources.forEach { mode ->
        outputs.file(modeSourceOutDir.resolve("${mode.name}.zip"))
            .withPropertyName("generated-${mode.name}")
    }

    doLast {
        if (!modeSourceTemplate.isFile) {
            throw GradleException(
                "Missing ULB template at ${modeSourceTemplate.path}. It is committed to the repo " +
                        "(see the .gitignore exception); nothing downloads it."
            )
        }
        if (!legacyChunkDefinitions.isFile) {
            throw GradleException("Missing legacy chunk definitions at ${legacyChunkDefinitions.path}")
        }

        val definitions = parseLegacyChunks(legacyChunkDefinitions.readText())
        val chapterCount = definitions.values.sumOf { it.size }
        if (chapterCount != definedChapters) {
            throw GradleException(
                "Legacy chunk definitions cover $chapterCount chapters, expected $definedChapters. " +
                        "A truncated file would silently change the chunking."
            )
        }
        val bookSlug = Regex("""\\id\s+(\w+)""")
        modeSourceOutDir.mkdirs()

        modeSources.forEach { mode ->
            val target = modeSourceOutDir.resolve("${mode.name}.zip")
            var rewritten = 0

            java.util.zip.ZipFile(modeSourceTemplate).use { source ->
                java.util.zip.ZipOutputStream(target.outputStream().buffered()).use { out ->
                    source.entries().asSequence().forEach { entry ->
                        val bytes = source.getInputStream(entry).use { it.readBytes() }
                        val body = when {
                            entry.name.endsWith("manifest.yaml") -> {
                                var manifest = bytes.decodeToString()
                                manifest = setDublinCoreField(manifest, "identifier", modeSourceIdentifier)
                                manifest = setDublinCoreField(manifest, "version", mode.version)
                                manifest = setDublinCoreField(manifest, "title", mode.title)
                                manifest.encodeToByteArray()
                            }

                            mode.mergeChunks && entry.name.endsWith(".usfm") -> {
                                val before = bytes.decodeToString()
                                val slug = bookSlug.find(before)?.groupValues?.get(1)?.lowercase()
                                val after = mergeChunkVerses(before, slug?.let { definitions[it] }.orEmpty())
                                if (after != before) rewritten++
                                after.encodeToByteArray()
                            }

                            else -> bytes
                        }
                        out.putNextEntry(java.util.zip.ZipEntry(entry.name).apply { time = entry.time })
                        if (!entry.isDirectory) out.write(body)
                        out.closeEntry()
                    }
                }
            }
            val detail = if (mode.mergeChunks) " ($rewritten books rewritten)" else ""
            println(
                "generateModeSources: ${target.name} -> " +
                        "'$modeSourceIdentifier' v${mode.version}$detail"
            )
        }

        verifyChunkMode(
            modeSourceOutDir.resolve("${modeSources.single { it.mergeChunks }.name}.zip"),
            definitions
        )
    }
}

/**
 * Fails the build unless the chunk-mode text reproduces the legacy chunking exactly, which is what
 * guarantees migrated audio stays attached to a unit describing the same verses.
 *
 * A singleton generated for a verse no legacy chunk covers is expected and does not count against
 * it; see the merge above.
 */
fun verifyChunkMode(zip: File, definitions: Map<String, Map<Int, List<IntRange>>>) {
    val actual = versesByBook(zip)
    val differing = mutableListOf<String>()
    var compared = 0

    definitions.forEach { (slug, chapters) ->
        val book = actual[slug] ?: run {
            differing += "$slug (no text)"
            return@forEach
        }
        chapters.forEach { (chapter, ranges) ->
            compared++
            val covered = ranges.flatMapTo(mutableSetOf()) { it }
            val produced = book[chapter]
                ?.filterNot { it.first == it.last && it.first !in covered }
                ?.toList()
            if (produced != ranges) differing += "$slug ch$chapter"
        }
    }

    if (differing.isNotEmpty()) {
        throw GradleException(
            "${differing.size} chapter(s) do not reproduce the legacy chunking: " +
                    differing.take(15).joinToString() +
                    ". The template must be a ULB revision with no verse bridges " +
                    "(en_ulb 65fcaa1513 or later)."
        )
    }
    println("generateModeSources: all $compared legacy chapters reproduced exactly")
}

// Every Compose resource task that reads composeResources/ must see the generated pair in place.
tasks.matching {
    val n = it.name
    n.contains("ComposeResources") ||
            n.startsWith("prepareComposeResourcesTask") ||
            n.startsWith("copyNonXmlValueResources") ||
            n.startsWith("convertXmlValueResources") ||
            n.startsWith("generateResourceAccessors")
}.configureEach {
    dependsOn("generateModeSources")
}

// ModeSourcesTest asserts against the real artifacts, so they must exist before tests run.
tasks.withType<Test>().configureEach {
    dependsOn("generateModeSources")
}
