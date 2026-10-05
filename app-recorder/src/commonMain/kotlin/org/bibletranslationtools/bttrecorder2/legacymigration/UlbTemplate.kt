package org.bibletranslationtools.bttrecorder2.legacymigration

/**
 * The English ULB texts that projects migrated from the old BTT-Recorder are created from.
 *
 * The old app divided every chapter one of two ways: verse by verse, or into its own chunks. A
 * project takes its division from its source's verses, so each template reproduces one of them
 * exactly, and a migrated project picks the template that matches how its chapters were recorded.
 *
 * Both are ULB editions (identifier `ulb`, the bundled ULB's creator), so a migrated book can move to
 * another ULB edition later. They ship only with the Android recorder, at [assetPath] under its
 * assets, because the old app only ever ran on Android.
 */
enum class UlbTemplate(val assetPath: String, val title: String) {

    /** The en_ulb text pinned at commit 65fcaa1, as published: one verse at a time. */
    VERSES("legacy-migration/en_ulb_verses.zip", "Unlocked Literal Bible"),

    /**
     * The same text with each of the old app's chunks as one bridged verse (`\v 4-6`). Generated
     * once by `scripts/generate-chunk-template.main.kts` from [VERSES] and the old app's chunk
     * definitions.
     */
    CHUNKS("legacy-migration/en_ulb_chunks.zip", "Unlocked Literal Bible - Chunked");

    companion object {
        /** The old app's chunk definitions, which [CHUNKS] reproduces. */
        const val LEGACY_CHUNKS_ASSET = "legacy-migration/legacy-chunks.json"
    }
}
