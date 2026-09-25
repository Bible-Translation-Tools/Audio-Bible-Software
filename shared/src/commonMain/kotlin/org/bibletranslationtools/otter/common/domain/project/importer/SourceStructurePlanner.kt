package org.bibletranslationtools.otter.common.domain.project.importer

import org.bibletranslationtools.otter.common.api.persistence.repositories.IVersificationRepository
import org.bibletranslationtools.otter.common.collections.OtterTree
import org.bibletranslationtools.otter.common.collections.OtterTreeNode
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.CollectionOrContent
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.ContentType
import org.bibletranslationtools.otter.common.domain.resourcecontainer.project.VersificationTreeBuilder
import org.bibletranslationtools.otter.common.domain.versification.StandardVersifications
import org.bibletranslationtools.otter.common.domain.versification.TextStructure
import org.bibletranslationtools.otter.common.domain.versification.Versification
import org.bibletranslationtools.otter.common.domain.versification.VersificationDetector
import org.bibletranslationtools.otter.common.domain.versification.VersificationMatch
import org.slf4j.LoggerFactory
import org.wycliffeassociates.resourcecontainer.ResourceContainer

/**
 * The versification whose book list decides which absent books still get an empty template, so
 * they stay recordable. It is the 66-book list the app has always used; the Copenhagen files also
 * list deuterocanonical books, which sources don't get templates for.
 */
private const val TEMPLATE_BOOKS_VERSIFICATION = "ulb"

/**
 * What a source import writes, and the versification its text was found to follow.
 *
 * @property match null when the source names no versification, or none could be read. The tree
 *   is then the parsed text, unchanged.
 */
data class SourceStructurePlan(
    val tree: OtterTree<CollectionOrContent>,
    val match: VersificationMatch?
)

/**
 * Decides the structure a source import writes.
 *
 * The source's own text is authoritative: every verse it contains is imported, bridges included,
 * and none is dropped. A versification only adds what the text leaves out:
 * - a verse missing inside a chapter (for example an omitted textual variant such as MRK 7:16),
 *   including a missing verse 1;
 * - a chapter missing inside a book, including a missing chapter 1;
 * - an empty template for each of the app's 66 books the text doesn't contain.
 *
 * It never adds verses after a chapter's last verse, or chapters after a book's last chapter. From
 * counts alone an unfinished source (a chapter that stops at verse 10) looks the same as a
 * numbering difference (English ULB folds Acts 19:41 into 19:40), and padding the second creates a
 * verse with no text.
 *
 * The versification is the standard one that best fits the text ([VersificationDetector]), not the
 * one the manifest declares: every bundled source declares `ufw` whatever it follows. A declared
 * versification still gates the whole step, so containers that aren't versified Bible text (no
 * versification named) import exactly as parsed.
 */
class SourceStructurePlanner(
    private val versificationRepository: IVersificationRepository,
    private val treeBuilder: VersificationTreeBuilder
) {
    private val logger = LoggerFactory.getLogger(SourceStructurePlanner::class.java)

    fun plan(container: ResourceContainer, parsedTree: OtterTree<CollectionOrContent>): SourceStructurePlan {
        val declared = container.manifest.projects.firstOrNull()?.versification
        if (declared.isNullOrBlank()) return SourceStructurePlan(parsedTree, null)

        val candidates = StandardVersifications.all.mapNotNull { code -> read(code)?.let { code to it } }
        val match = VersificationDetector.detect(textStructureOf(parsedTree), candidates)
            ?: return SourceStructurePlan(parsedTree, null).also {
                logger.warn("No standard versification could be read; importing the source text as parsed")
            }
        logger.info(
            "Source text follows '${match.code}' (declared '$declared'); " +
                "${match.differingChapters.size} chapter(s) differ: ${match.differingChapters.take(20)}"
        )

        val templateBooks = read(TEMPLATE_BOOKS_VERSIFICATION)?.getBookSlugs().orEmpty()
        val tree = fillStructureGaps(parsedTree, match.versification, templateBooks) { book ->
            treeBuilder.bookTree(match.versification, container, book, match.code)
        }
        return SourceStructurePlan(tree, match)
    }

    private fun read(code: String): Versification? =
        runCatching { versificationRepository.getVersification(code).blockingGet() }
            .onFailure { logger.error("Could not read versification '$code'", it) }
            .getOrNull()

    /**
     * [parsed] with the gaps [versification] declares filled in, as a new tree; [parsed] itself is
     * not changed. Books in [templateBooks] that the text doesn't contain are added from
     * [bookTemplate], at the root.
     */
    internal fun fillStructureGaps(
        parsed: OtterTree<CollectionOrContent>,
        versification: Versification,
        templateBooks: List<String>,
        bookTemplate: (String) -> OtterTree<CollectionOrContent>
    ): OtterTree<CollectionOrContent> {
        val presentBooks = mutableSetOf<String>()
        val root = rebuild(parsed, versification, presentBooks)
        templateBooks
            .filter { it !in presentBooks && versification.getChaptersInBook(it) > 0 }
            .forEach { root.addChild(bookTemplate(it)) }
        return root
    }

    private fun rebuild(
        node: OtterTree<CollectionOrContent>,
        versification: Versification,
        presentBooks: MutableSet<String>
    ): OtterTree<CollectionOrContent> {
        if (node.isBook()) {
            val slug = (node.value as Collection).slug
            presentBooks.add(slug)
            return fillBook(node, slug, versification)
        }
        return OtterTree(node.value).apply {
            node.children.forEach { child ->
                addChild(if (child is OtterTree) rebuild(child, versification, presentBooks) else child)
            }
        }
    }

    private fun fillBook(
        book: OtterTree<CollectionOrContent>,
        slug: String,
        versification: Versification
    ): OtterTree<CollectionOrContent> {
        val chapters = book.chapterTrees().associateBy { (it.value as Collection).sort }
        val lastChapter = chapters.keys.maxOrNull() ?: return book
        val declaredChapters = versification.getChaptersInBook(slug)

        return OtterTree(book.value).apply {
            book.children.filter { it !is OtterTree || it.value !is Collection }.forEach(::addChild)
            for (number in chapters.keys.plus(1..minOf(lastChapter, declaredChapters)).sorted()) {
                val chapter = chapters[number]
                addChild(
                    if (chapter != null) fillChapter(chapter, slug, number, versification)
                    else treeBuilder.chapterTree(versification, slug, number)
                )
            }
        }
    }

    private fun fillChapter(
        chapter: OtterTree<CollectionOrContent>,
        book: String,
        number: Int,
        versification: Versification
    ): OtterTree<CollectionOrContent> {
        val verses = chapter.contents().filter { it.type == ContentType.TEXT }
        val covered = verses.flatMap { it.start..it.end }.toSet()
        val lastVerse = covered.maxOrNull() ?: return chapter
        val declaredVerses = versification.getVersesInChapter(book, number)
        val missing = (1..minOf(lastVerse, declaredVerses)).filter { it !in covered }
        if (missing.isEmpty()) return chapter

        val firstVerse = minOf(covered.min(), missing.min())
        return OtterTree<CollectionOrContent>(chapter.value).apply {
            chapter.children.forEach { child ->
                val content = child.value as? Content
                // The chapter chunk and titles span the chapter; widen them to a newly added verse 1.
                if (content != null && content.type != ContentType.TEXT && content.start > firstVerse) {
                    addChild(OtterTreeNode(content.copy(start = firstVerse)))
                } else {
                    addChild(child)
                }
            }
            missing.forEach { addChild(OtterTreeNode(treeBuilder.verseContent(it))) }
        }
    }
}

/** The last verse of each chapter of each book in [tree], as its text declares them. */
internal fun textStructureOf(tree: OtterTree<CollectionOrContent>): TextStructure {
    val books = mutableMapOf<String, Map<Int, Int>>()
    fun walk(node: OtterTree<CollectionOrContent>) {
        if (node.isBook()) {
            books[(node.value as Collection).slug] = node.chapterTrees()
                .associate { chapter ->
                    (chapter.value as Collection).sort to
                        (chapter.contents().filter { it.type == ContentType.TEXT }.maxOfOrNull { it.end } ?: 0)
                }
                .filterValues { it > 0 }
        } else {
            node.children.filterIsInstance<OtterTree<CollectionOrContent>>().forEach(::walk)
        }
    }
    walk(tree)
    return TextStructure(books)
}

/** A book is a collection whose child collections (its chapters) hold content. */
private fun OtterTree<CollectionOrContent>.isBook(): Boolean =
    value is Collection && chapterTrees().any { chapter -> chapter.children.any { it.value is Content } }

private fun OtterTree<CollectionOrContent>.chapterTrees(): List<OtterTree<CollectionOrContent>> =
    children.filterIsInstance<OtterTree<CollectionOrContent>>().filter { it.value is Collection }

private fun OtterTree<CollectionOrContent>.contents(): List<Content> =
    children.mapNotNull { it.value as? Content }
