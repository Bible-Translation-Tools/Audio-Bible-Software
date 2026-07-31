package org.bibletranslationtools.otter.common.domain.project.importer

import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.data.primitives.ContentLabel
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.Collection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The source-import branch that was switched off for the whole port.
 *
 * `NewSourceImporter` hard-coded its versification tree to `null` (commit "Get Initialization
 * working", whose message says "there's an issue with pulling the versification, so it's commented
 * out"), which made the versification arm unreachable. Nothing pinned either arm, so nothing noticed
 * when the underlying cause — the bundled versification file never reaching disk, fixed in
 * InitializeVersification — went away.
 */
class PlanImportTest {

    private fun collection(slug: String) = Collection(
        sort = 1,
        slug = slug,
        labelKey = "book",
        titleKey = slug.uppercase(),
        resourceContainer = null
    )

    private fun verse(sort: Int) = Content(
        sort = sort,
        labelKey = ContentLabel.VERSE.value,
        start = sort,
        end = sort,
        selectedTake = null,
        text = null,
        format = "text/usfm",
        type = ContentType.TEXT,
        draftNumber = 1
    )

    private fun tree(value: CollectionOrContent, vararg children: CollectionOrContent) =
        OtterTree<CollectionOrContent>(value).apply {
            children.forEach { addChild(OtterTree(it)) }
        }

    // ── no versification ─────────────────────────────────────────────────────────────────

    /**
     * The behaviour the app had for the entire period the versification arm was disabled: import
     * exactly what the source text contains. Still the fallback when a source declares no
     * versification, so it has to keep working.
     */
    @Test
    fun `without a versification the parsed tree is imported as-is`() {
        val parsed = tree(collection("gen"), verse(1))

        val plan = planImport(collection("gen"), parsed, versificationTrees = null)

        assertSame(parsed, plan.treeToImport, "the parsed tree is imported unchanged")
        assertFalse(
            plan.applyParsedTextAfter,
            "the parsed tree already carries its text; re-applying it would be a second write"
        )
    }

    /**
     * An empty list is treated as no versification. `VersificationTreeBuilder.build` returns null
     * when the container names no versification, but a versification that yields no books would
     * otherwise produce a preallocation tree with no chapters at all — importing that would replace
     * the real structure with nothing.
     */
    @Test
    fun `an empty versification is treated as no versification`() {
        val parsed = tree(collection("gen"), verse(1))

        val plan = planImport(collection("gen"), parsed, versificationTrees = emptyList())

        assertSame(parsed, plan.treeToImport)
        assertFalse(plan.applyParsedTextAfter)
    }

    // ── with a versification ─────────────────────────────────────────────────────────────

    @Test
    fun `with a versification the preallocated structure is imported instead of the parsed tree`() {
        val parsed = tree(collection("gen"), verse(1))
        val fromVersification = listOf(tree(collection("gen"), verse(1), verse(2), verse(3)))

        val plan = planImport(collection("gen"), parsed, fromVersification)

        assertTrue(plan.treeToImport !== parsed, "the versification structure supersedes the text's")
        assertTrue(
            plan.applyParsedTextAfter,
            "the preallocated content has no text yet — it must be filled in afterwards"
        )
    }

    /** Every book the versification declares has to reach the import, not just the first. */
    @Test
    fun `all versification books are grafted onto the container collection`() {
        val parsed = tree(collection("gen"))
        val fromVersification = listOf(
            tree(collection("gen"), verse(1)),
            tree(collection("exo"), verse(1)),
            tree(collection("lev"), verse(1))
        )

        val plan = planImport(collection("bible"), parsed, fromVersification)

        assertEquals(
            listOf("gen", "exo", "lev"),
            plan.treeToImport.children.map { (it.value as Collection).slug },
            "each versification book becomes a child, in order"
        )
    }

    /** The tree is rooted on the container's own collection, not on the first versification book. */
    @Test
    fun `the preallocated tree is rooted on the container collection`() {
        val containerCollection = collection("ulb")
        val fromVersification = listOf(tree(collection("gen"), verse(1)))

        val plan = planImport(containerCollection, tree(collection("gen")), fromVersification)

        assertSame(containerCollection, plan.treeToImport.value)
    }

    /**
     * The parsed tree must not be mutated: it is passed to `updateContent` afterwards to supply the
     * text, so grafting onto it instead of a fresh root would import and then re-import the same
     * modified structure.
     */
    @Test
    fun `the parsed tree is left untouched`() {
        val parsed = tree(collection("gen"), verse(1))
        val childrenBefore = parsed.children.size

        planImport(collection("gen"), parsed, listOf(tree(collection("gen"), verse(1), verse(2))))

        assertEquals(childrenBefore, parsed.children.size)
    }
}
