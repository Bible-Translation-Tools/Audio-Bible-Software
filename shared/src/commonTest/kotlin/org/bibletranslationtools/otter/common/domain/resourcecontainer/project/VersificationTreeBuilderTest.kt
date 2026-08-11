package org.bibletranslationtools.otter.common.domain.resourcecontainer.project

import io.mockk.every
import io.mockk.mockk
import io.reactivex.Maybe
import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.collections.OtterTreeNode
import org.bibletranslationtools.otter.common.data.primitives.BOOK_TITLE_SORT
import org.bibletranslationtools.otter.common.data.primitives.CHAPTER_TITLE_SORT
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentLabel
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.versification.Versification
import org.wycliffeassociates.resourcecontainer.ResourceContainer
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.resourcecontainer.entity.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The structure a source import pre-allocates, which had no test at all.
 *
 * That mattered less while `NewSourceImporter` hard-coded its versification tree to null — this
 * class was unreachable in production. Now that the path is restored, this is what decides which
 * chapters and verses exist in a project, so its shape is worth pinning precisely.
 */
class VersificationTreeBuilderTest {

    private val versificationRepository: IVersificationRepository = mockk()

    /** A [Versification] over a literal book → chapter → verse-count map. */
    private class FakeVersification(
        private val books: Map<String, List<Int>>
    ) : Versification {
        override fun getBookSlugs(): List<String> = books.keys.toList()
        override fun getChaptersInBook(bookSlug: String): Int = books[bookSlug]?.size ?: 0
        override fun getVersesInChapter(bookSlug: String, chapterNumber: Int): Int =
            books[bookSlug]?.getOrNull(chapterNumber - 1) ?: 0
    }

    private fun project(identifier: String, versification: String = "ulb") = Project(
        title = identifier.uppercase(),
        versification = versification,
        identifier = identifier,
        sort = 1,
        path = "./$identifier",
        categories = listOf()
    )

    private fun container(projects: List<Project>): ResourceContainer {
        val manifest: Manifest = mockk { every { this@mockk.projects } returns projects }
        return mockk { every { this@mockk.manifest } returns manifest }
    }

    private fun builderFor(
        books: Map<String, List<Int>>,
        slug: String = "ulb"
    ): VersificationTreeBuilder {
        every { versificationRepository.getVersification(slug) } returns
            Maybe.just(FakeVersification(books))
        return VersificationTreeBuilder(versificationRepository)
    }

    private val OtterTreeNode<CollectionOrContent>.asTree: OtterTree<CollectionOrContent>
        get() = this as OtterTree<CollectionOrContent>

    private fun OtterTree<CollectionOrContent>.contents(): List<Content> =
        children.map { it.value }.filterIsInstance<Content>()

    // ── no versification ─────────────────────────────────────────────────────────────────

    /** Null, not an empty list — the importer's fallback is keyed on it. */
    @Test
    fun `returns null when the container declares no projects`() {
        val builder = builderFor(mapOf("gen" to listOf(1)))

        assertNull(builder.build(container(emptyList())))
    }

    @Test
    fun `returns null when the versification code is blank`() {
        val builder = builderFor(mapOf("gen" to listOf(1)))

        assertNull(builder.build(container(listOf(project("gen", versification = "")))))
    }

    // ── structure ────────────────────────────────────────────────────────────────────────

    /**
     * One tree per book the VERSIFICATION declares, not per book the container ships. This is the
     * whole point of pre-allocation: a container carrying only Genesis still gets the versification's
     * other books, so they are recordable without re-importing.
     */
    @Test
    fun `builds a tree for every book in the versification, not just those in the container`() {
        val builder = builderFor(mapOf("gen" to listOf(2), "exo" to listOf(3), "lev" to listOf(1)))

        val trees = builder.build(container(listOf(project("gen"))))!!

        assertEquals(
            listOf("gen", "exo", "lev"),
            trees.map { (it.value as Collection).slug }
        )
    }

    @Test
    fun `builds one chapter per chapter in the book, numbered from one`() {
        val builder = builderFor(mapOf("gen" to listOf(5, 5, 5)))

        val gen = builder.build(container(listOf(project("gen"))))!!.single()

        assertEquals(
            listOf(1, 2, 3),
            gen.children.map { (it.value as Collection).sort }
        )
        assertEquals(
            listOf("gen_1", "gen_2", "gen_3"),
            gen.children.map { (it.value as Collection).slug }
        )
    }

    @Test
    fun `builds one TEXT content per verse in the chapter`() {
        val builder = builderFor(mapOf("gen" to listOf(3)))

        val chapter = builder.build(container(listOf(project("gen"))))!!.single()
            .children.single().asTree

        val verses = chapter.contents().filter { it.type == ContentType.TEXT }
        assertEquals(listOf(1, 2, 3), verses.map { it.sort })
        assertTrue(verses.all { it.start == it.sort && it.end == it.sort })
        assertTrue(verses.all { it.text == null }, "pre-allocated verses carry no text yet")
    }

    /**
     * Each chapter carries a META chunk at sort 0 (the whole-chapter recording) and a TITLE at
     * [CHAPTER_TITLE_SORT]; the negative sort is what keeps the title ahead of verse 1.
     */
    @Test
    fun `each chapter carries a meta chunk and a chapter title`() {
        val builder = builderFor(mapOf("gen" to listOf(4)))

        val chapter = builder.build(container(listOf(project("gen"))))!!.single()
            .children.single().asTree

        val meta = chapter.contents().single { it.type == ContentType.META }
        assertEquals(0, meta.sort)
        assertEquals(4, meta.end, "the chapter chunk spans every verse")

        val title = chapter.contents().single {
            it.type == ContentType.TITLE && it.sort == CHAPTER_TITLE_SORT
        }
        assertEquals(ContentLabel.CHAPTER.value, title.labelKey)
    }

    /** The book title belongs to the book, so it is added once rather than to every chapter. */
    @Test
    fun `only the first chapter carries the book title`() {
        val builder = builderFor(mapOf("gen" to listOf(2, 2, 2)))

        val chapters = builder.build(container(listOf(project("gen"))))!!.single().children

        val bookTitleCounts = chapters.map { chapter ->
            chapter.asTree.contents().count { it.sort == BOOK_TITLE_SORT }
        }
        assertEquals(listOf(1, 0, 0), bookTitleCounts)
    }

    /** Psalms are labelled "psalm", not "chapter" (ChapterLabel.of). */
    @Test
    fun `psalms chapters use the psalm label`() {
        val builder = builderFor(mapOf("psa" to listOf(6)))

        val chapter = builder.build(container(listOf(project("psa"))))!!.single()
            .children.single().asTree

        assertEquals(ContentLabel.PSALM.value, (chapter.value as Collection).labelKey)
    }

    @Test
    fun `chapters of other books use the chapter label`() {
        val builder = builderFor(mapOf("gen" to listOf(6)))

        val chapter = builder.build(container(listOf(project("gen"))))!!.single()
            .children.single().asTree

        assertEquals(ContentLabel.CHAPTER.value, (chapter.value as Collection).labelKey)
    }
}
