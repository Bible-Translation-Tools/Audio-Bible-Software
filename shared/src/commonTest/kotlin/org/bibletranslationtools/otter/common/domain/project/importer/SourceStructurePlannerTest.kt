package org.bibletranslationtools.otter.common.domain.project.importer

import io.mockk.every
import io.mockk.mockk
import io.reactivex.Maybe
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.collections.OtterTreeNode
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentLabel
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.VersificationTreeBuilder
import org.bibletranslationtools.otter.common.domain.versification.Versification
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.resourcecontainer.entity.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The structure a source import writes: the source's own text, plus only the gaps a versification
 * fills inside it. Never verses after a chapter's text, never text dropped.
 */
class SourceStructurePlannerTest {

    private class FakeVersification(private val books: Map<String, List<Int>>) : Versification {
        override fun getBookSlugs(): List<String> = books.keys.toList()
        override fun getChaptersInBook(bookSlug: String): Int = books[bookSlug]?.size ?: 0
        override fun getVersesInChapter(bookSlug: String, chapterNumber: Int): Int =
            books[bookSlug]?.getOrNull(chapterNumber - 1) ?: 0
    }

    private val repository: IVersificationRepository = mockk()
    private val treeBuilder = VersificationTreeBuilder(repository)
    private val planner = SourceStructurePlanner(repository, treeBuilder)

    // ── tree helpers ─────────────────────────────────────────────────────────────────────

    private fun collection(slug: String, sort: Int = 1) = Collection(
        sort = sort,
        slug = slug,
        labelKey = "book",
        titleKey = slug,
        resourceContainer = null
    )

    private fun verse(start: Int, end: Int = start, text: String? = "text $start") = Content(
        sort = start,
        labelKey = ContentLabel.VERSE.value,
        start = start,
        end = end,
        selectedTake = null,
        text = text,
        format = "text/usfm",
        type = ContentType.TEXT,
        draftNumber = 1
    )

    private fun meta(start: Int, end: Int) = Content(
        sort = 0,
        labelKey = ContentLabel.CHAPTER.value,
        start = start,
        end = end,
        selectedTake = null,
        text = null,
        format = "text/usfm",
        type = ContentType.META,
        draftNumber = 1
    )

    private fun chapter(book: String, number: Int, vararg contents: Content) =
        OtterTree<CollectionOrContent>(collection("${book}_$number", number)).apply {
            contents.forEach { addChild(OtterTreeNode(it)) }
        }

    private fun book(slug: String, vararg chapters: OtterTree<CollectionOrContent>) =
        OtterTree<CollectionOrContent>(collection(slug)).apply { chapters.forEach(::addChild) }

    private fun root(vararg books: OtterTree<CollectionOrContent>) =
        OtterTree<CollectionOrContent>(collection("container")).apply { books.forEach(::addChild) }

    private fun fill(
        parsed: OtterTree<CollectionOrContent>,
        versification: Versification,
        templateBooks: List<String> = emptyList()
    ) = planner.fillStructureGaps(parsed, versification, templateBooks) { slug ->
        OtterTree(collection(slug))
    }

    private fun OtterTree<CollectionOrContent>.child(slug: String): OtterTree<CollectionOrContent> =
        children.filterIsInstance<OtterTree<CollectionOrContent>>().single { (it.value as Collection).slug == slug }

    private fun OtterTree<CollectionOrContent>.verses(): List<Content> =
        children.mapNotNull { it.value as? Content }.filter { it.type == ContentType.TEXT }.sortedBy { it.start }

    // ── verses ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a verse missing inside a chapter gets an empty row`() {
        val parsed = root(book("mrk", chapter("mrk", 1, verse(1), verse(2), verse(4))))

        val chapter = fill(parsed, FakeVersification(mapOf("mrk" to listOf(4)))).child("mrk").child("mrk_1")

        assertEquals(listOf(1, 2, 3, 4), chapter.verses().map { it.start })
        assertNull(chapter.verses().single { it.start == 3 }.text, "the added row has no text")
    }

    @Test
    fun `a missing verse 1 is filled and the chapter chunk widened to it`() {
        val parsed = root(book("gen", chapter("gen", 1, meta(2, 3), verse(2), verse(3))))

        val chapter = fill(parsed, FakeVersification(mapOf("gen" to listOf(3)))).child("gen").child("gen_1")

        assertEquals(listOf(1, 2, 3), chapter.verses().map { it.start })
        assertEquals(1, chapter.children.mapNotNull { it.value as? Content }.single { it.type == ContentType.META }.start)
    }

    /** Acts 19 in the English ULB ends at 40; `eng` says 41. No empty 41 is added. */
    @Test
    fun `verses after the text's last verse are never added`() {
        val parsed = root(book("act", chapter("act", 19, verse(39), verse(40))))
        val eng = FakeVersification(mapOf("act" to List(18) { 1 } + 41))

        val chapter = fill(parsed, eng).child("act").child("act_19")

        assertEquals((1..40).toList(), chapter.verses().map { it.start })
    }

    /** Russian text numbered past what the versification declares must not lose those verses. */
    @Test
    fun `text beyond the versification's count is kept`() {
        val parsed = root(book("num", chapter("num", 1, verse(1), verse(2), verse(3))))

        val chapter = fill(parsed, FakeVersification(mapOf("num" to listOf(2)))).child("num").child("num_1")

        assertEquals(listOf(1, 2, 3), chapter.verses().map { it.start })
        assertEquals("text 3", chapter.verses().last().text)
    }

    @Test
    fun `a bridge covers its verses, so nothing is added inside it`() {
        val parsed = root(book("jhn", chapter("jhn", 1, verse(1), verse(2, 4), verse(5))))

        val chapter = fill(parsed, FakeVersification(mapOf("jhn" to listOf(5)))).child("jhn").child("jhn_1")

        assertEquals(listOf(1 to 1, 2 to 4, 5 to 5), chapter.verses().map { it.start to it.end })
    }

    @Test
    fun `a complete chapter is passed through unchanged`() {
        val complete = chapter("gen", 1, verse(1), verse(2))
        val parsed = root(book("gen", complete))

        val filled = fill(parsed, FakeVersification(mapOf("gen" to listOf(2)))).child("gen").child("gen_1")

        assertSame(complete, filled)
    }

    // ── chapters and books ───────────────────────────────────────────────────────────────

    @Test
    fun `a chapter missing inside a book gets an empty chapter`() {
        val parsed = root(book("gen", chapter("gen", 1, verse(1)), chapter("gen", 3, verse(1))))

        val book = fill(parsed, FakeVersification(mapOf("gen" to listOf(1, 2, 1, 1)))).child("gen")

        assertEquals(listOf(1, 2, 3), book.children.map { (it.value as Collection).sort })
        assertEquals(2, book.child("gen_2").verses().size, "the empty chapter has the versification's verses")
    }

    @Test
    fun `chapters after the book's last chapter are never added`() {
        val parsed = root(book("mal", chapter("mal", 1, verse(1)), chapter("mal", 3, verse(1))))

        val book = fill(parsed, FakeVersification(mapOf("mal" to listOf(1, 1, 1, 6)))).child("mal")

        assertEquals(listOf(1, 2, 3), book.children.map { (it.value as Collection).sort })
    }

    @Test
    fun `books the text lacks get templates only from the template list`() {
        val parsed = root(book("mat", chapter("mat", 1, verse(1))))
        val versification = FakeVersification(mapOf("mat" to listOf(1), "gen" to listOf(1), "tob" to listOf(1)))

        val filled = fill(parsed, versification, templateBooks = listOf("gen", "mat"))

        assertEquals(listOf("mat", "gen"), filled.children.map { (it.value as Collection).slug })
    }

    @Test
    fun `the parsed tree is not changed`() {
        val parsed = root(book("mrk", chapter("mrk", 1, verse(1), verse(3))))

        fill(parsed, FakeVersification(mapOf("mrk" to listOf(3))))

        assertEquals(listOf(1, 3), parsed.child("mrk").child("mrk_1").verses().map { it.start })
    }

    // ── plan ─────────────────────────────────────────────────────────────────────────────

    private fun container(versification: String): ResourceContainer {
        val project = Project(
            title = "Genesis", versification = versification, identifier = "gen",
            sort = 1, path = "./gen.usfm", categories = listOf()
        )
        val manifest: Manifest = mockk { every { projects } returns listOf(project) }
        return mockk { every { this@mockk.manifest } returns manifest }
    }

    @Test
    fun `a container that names no versification is imported as parsed`() {
        val parsed = root(book("gen", chapter("gen", 1, verse(1), verse(3))))

        val plan = planner.plan(container(versification = ""), parsed)

        assertSame(parsed, plan.tree)
        assertNull(plan.match)
    }

    @Test
    fun `the closest standard versification is used, not the declared one`() {
        every { repository.getVersification(any()) } returns Maybe.empty()
        every { repository.getVersification("eng") } returns Maybe.just(FakeVersification(mapOf("gen" to listOf(5))))
        every { repository.getVersification("rsc") } returns Maybe.just(FakeVersification(mapOf("gen" to listOf(3))))
        val parsed = root(book("gen", chapter("gen", 1, verse(1), verse(3))))

        val plan = planner.plan(container(versification = "ufw"), parsed)

        assertEquals("rsc", plan.match?.code)
        assertEquals(listOf(1, 2, 3), plan.tree.child("gen").child("gen_1").verses().map { it.start })
    }
}
