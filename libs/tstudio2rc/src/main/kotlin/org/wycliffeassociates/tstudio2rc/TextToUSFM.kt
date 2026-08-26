package org.wycliffeassociates.tstudio2rc

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.wycliffeassociates.tstudio2rc.entity.BookVersification
import java.io.BufferedWriter
import java.io.File

/**
 * Ported from https://github.com/unfoldingWord-dev/tools/blob/develop/usfm/txt2USFM.py
 */
internal class TextToUSFM {

    private lateinit var inputDir: String
    private lateinit var targetDir: String
    private var languageCode = ""

    private val usfmVerses = getVersification()
    private val verseMarkerRe = Regex("[ \\n\\t]*\\\\v *([\\d]{1,3})")
    private val verseTagsRe = Regex("\\\\v +[^1-9]")
    private val numbersRe = Regex("[ \\n]([\\d]{1,3})[ \\n]")
    private val numberStartRe = Regex("([\\d]{1,3})[ \\n]")
    private val chapMarkerRe = Regex("\\\\c *[\\d]{1,3}")
    private val chapLabelRe = Regex("\\\\cl")

    val contributors = mutableListOf<String>()
    val projects = mutableListOf<Any>()

    fun cleanupChunk(directory: File, filename: String, verseRange: List<String>) {
        val vnStart = verseRange.first().toInt()
        val vnEnd = verseRange.last().toInt()
        val path = File(directory, filename)
        val input = path.bufferedReader()
        val origText = input.readText()
        input.close()
        var text = fixVerseMarkers(origText)
        text = fixChapterMarkers(text)
        text = fixPunctuationSpacing(text)

        var missingChapter = ""
        if (vnStart == 1 && lacksChapter(text)) {
            missingChapter = "${directory.name.toInt()}"
        }
        val missingVerses = lackingVerses(text, verseRange, numbersRe)
        val missingMarkers = lackingVerses(text, verseRange, verseMarkerRe)
        if (missingChapter.isNotEmpty() || missingVerses.isNotEmpty() || missingMarkers.isNotEmpty()) {
            if (verseTagsRe.containsMatchIn(text)) {
                if (missingVerses.isNotEmpty()) {
                    text = ensureNumbers(text, missingVerses)
                }
            }
            text = ensureMarkers(text, missingChapter, vnStart, vnEnd, missingVerses, missingMarkers)
        }
        if (languageCode == "ior") {
            text = fixInorMarkers(text, verseRange)
        }

        if (text != origText) {
            // TODO: Should we get rid of these .orig back up files?
//            val bakPath = File("$path.orig")
//            if (!bakPath.exists()) {
//                path.copyTo(bakPath)
//            }
            path.bufferedWriter().use {
                it.write(text)
            }
        }
    }

    fun lacksChapter(text: String): Boolean {
        val verseMarker = verseMarkerRe.find(text)
        return (verseMarker == null || !chapMarkerRe.containsMatchIn(text.substring(0, verseMarker.range.start)))
    }

    fun lackingVerses(str: String, verseRange: List<String>, exprRe: Regex): List<String> {
        val missingVerses = mutableListOf<String>()
        val numbers = exprRe.findAll(str).map { it.groupValues[1] }
        verseRange.forEach { verse ->
            if (verse !in numbers) {
                missingVerses.add(verse)
            }
        }
        return missingVerses
    }

    val numberMatchRe = Regex("[ \\n\\t]*([\\d]{1,3}[ \\n])")
    val untaggedNumberRe = Regex("[^v][ \\n]([\\d]{1,3}[ \\n])")

    fun ensureMarkers(
        text: String,
        missingChapter: String,
        vnStart: Int,
        vnEnd: Int,
        missingVerses: List<String>,
        missingMarkers: List<String>
    ): String {
        var text = text
        var goodStr = ""
        if (missingChapter.isNotEmpty()) {
            goodStr = "\\c $missingChapter\n"
        }
        if (missingVerses.isEmpty() && missingMarkers.isEmpty()) {
            goodStr += text
        } else {
            val chap = chapMarkerRe.find(text)
            if (chap != null) {
                goodStr += "${text.substring(0, chap.range.endInclusive + 1)}\n"
            }
            val verseAtStart = numberStartRe.find(text) ?: verseMarkerRe.find(text)
            if (missingVerses.isNotEmpty() || missingMarkers.isNotEmpty()) {
                if (verseAtStart == null) {
                    val startVV = missingStartVV(vnStart, vnEnd, text)
                    goodStr += "$startVV"
                }
                var number = numberMatchRe.find(text)
                while (number != null) {
                    val verse = number.groupValues[1].dropLast(1)
                    if (verse in missingMarkers) {
                        goodStr += "${text.substring(0, number.range.start + 1)}\\v $verse"
                    } else {
                        goodStr += text.substring(0, number.range.endInclusive + 1)
                    }
                    text = text.substring(number.range.endInclusive + 1)
                    number = untaggedNumberRe.find(text)
                }
            }
            goodStr += text
        }
        return goodStr
    }

    fun missingStartVV(vnStart: Int, vnEnd: Int, text: String): String {
        var firstVerseFound = verseMarkerRe.find(text)?.groupValues?.get(1)?.toInt() ?: 999
        var vn = vnStart
        while (vn < firstVerseFound - 1 && vn < vnEnd) {
            vn++
        }
        val startVV = if (vnStart == vn) "\\v $vn " else "\\v $vn-$vn "
        return startVV
    }

    fun ensureNumbers(text: String, missingVerses: List<String>): String {
        var newText = text
        missingVerses.forEach { verse ->
            val verseTag = verseTagsRe.find(text)
            if (verseTag != null) {
                newText = "${
                    text.substring(
                        0,
                        verseTag.range.endInclusive
                    )
                } $verse ${text.substring(verseTag.range.endInclusive)}"
            }
        }
        return newText
    }

    val sub0Re = Regex("""/v +[1-9]""")      // slash v
    val sub0bRe = Regex("""\\\\v +[1-9]""")  // double backslash v
    val sub1Re = Regex("""[^\n ]\\\\v """)     // no space before \v
    val sub2Re = Regex("""[\n .,"'?!]\\\\ *v[1-9]""")   // no space before verse number, possible space between \ and v
    val sub2mRe = Regex("""\\\\ *v[1-9]""")       // no space before verse number, possible space between \ and v -- match
    val sub3Re = Regex("""\\v +[0-9\-]+[^0-9\-\n ]""")      // no space after verse number
    val sub4Re = Regex("""(\\\\v +[0-9\\-]+ +)\\\\v +[^1-9]""")   // \v 10 \v The...
    val sub5Re = Regex("""\\\\v( +\\\\v +[0-9\\-]+ +)""")         // \v \v 10
    val sub6Re = Regex("""[\n ]\\\\ v [1-9]""")           // space between \ and v
    val sub6mRe = Regex("""\\\\ v [1-9]""")               // space between \ and v -- match
    val sub7Re = Regex("""[\n ]v [1-9]""")              // missing backslash
    val sub8Re = Regex("""(.)([\n ]*\\\\v [1-9]+) ?([.,:;]) """)   // Punctuation after verse marker
    val sub9Re = Regex("""(\\\\v [1-9]+) ?([.,:;]) """)

    fun fixVerseMarkers(text: String): String {
        var result = text
        sub0Re.findAll(text).forEach {
            result = result.replaceRange(it.range.first + 1, it.range.first + 2, "\\")
        }
        sub0bRe.findAll(text).forEach {
            result = result.removeRange(it.range.first + 1, it.range.first + 2)
        }
        sub1Re.findAll(text).forEach {
            result = result.replaceRange(it.range.first + 1, it.range.last - 1, "\n")
        }
        sub2mRe.find(text)?.let {
            result = "\\v " + text.substring(it.range.last)
        }
        sub2Re.findAll(text).forEach {
            result = result.replaceRange(it.range.first + 1, it.range.last - 1, "\n\\v ")
        }
        sub3Re.findAll(text).forEach {
            result = result.replaceRange(it.range.last - 1, it.range.last, " ")
        }
        sub4Re.findAll(text).forEach {
            result = result.replaceRange(it.range.first, it.range.last, it.groupValues[1])
        }
        sub5Re.findAll(text).forEach {
            result = result.replaceRange(it.range.first, it.range.last, it.groupValues[1])
        }
        sub6mRe.find(text)?.let {
            result = "\\v " + text.substring(it.range.last)
        }
        sub6Re.findAll(text).forEach {
            result = result.replaceRange(it.range.first, it.range.last, "\n\\v ")
        }
        sub7Re.findAll(text).forEach {
            result = result.replaceRange(it.range.first, it.range.last, "\n\\v ")
        }
        sub8Re.findAll(text).forEach {
            result = if (it.groupValues[3] != it.groupValues[1]) {
                result.replaceRange(it.range.first + 1, it.range.last - 1, it.groupValues[3] + it.groupValues[2])
            } else {
                result.replaceRange(it.range.first + 1, it.range.last - 1, it.groupValues[2])
            }
        }
        sub9Re.find(text)?.let {
            result = it.groupValues[1] + text.substring(it.range.last)
        }
        return result
    }


    // Combine lines of text, eliminating unwanted line breaks, tabs, and extra whitespace.
// Place most markers at the beginning of lines.
    fun combineLines(lines: List<String>): String {
        var section = ""
        for (line in lines) {
            var processedLine = line.replace("\t", " ")
                .replace("   ", " ")
                .replace("  ", " ")
                .replace(" \\", "\n\\")
                .trim()

            if (processedLine.isNotEmpty()) {
                section = if (section.isEmpty()) processedLine else {
                    if (processedLine.startsWith("\\") || processedLine.startsWith("==") || processedLine.startsWith(">>")) {
                        "$section\n$processedLine"
                    } else {
                        "$section $processedLine"
                    }
                }
            }
        }
        return section
    }

    val cvExpr = Regex("""\\[cv] [0-9]+""")
    val chapterRe = Regex("""\n\\c +[0-9]+[ \n]*""")

    // Adds section marker, chapter label, and paragraph marker as needed.
    fun augmentChapter(section: String, chapterTitle: String?): String {
        var modifiedSection = section
        modifiedSection = chapterRe.replace(modifiedSection) {
            val clpStr = if (chapterTitle != null) "\n\\cl $chapterTitle\n\\p\n" else "\n\\p\n"
            "${it.value.trimEnd()}$clpStr"
        }
        return modifiedSection
    }

    val spaceDotRe = Regex("""[^0-9] [\.\?!;\:,][^\.]""")
    val jammed = Regex("""[\.\?!;:,)][\w]""")

    // Removes extraneous space before clause ending punctuation and adds space after
// sentence/clause end if needed.
    fun fixPunctuationSpacing(section: String): String {
        var modifiedSection = section
        modifiedSection = spaceDotRe.replace(modifiedSection) { it.value.replace(" ", "") }
        modifiedSection = jammed.replace(modifiedSection) {
            if ((it.groupValues.getOrNull(1) ?: "") !in "0123456789") {
                "${it.groupValues[0]} ${it.groupValues[1]}"
            } else {
                it.value
            }
        }
        return modifiedSection
    }

    // Inserts space between \c and the chapter number if needed
    fun fixChapterMarkers(section: String): String {
        return section.replace(Regex("""\\c[0-9]""")) { "${it.value[0]} ${it.value[1]}" }
    }

    val stripCvRe = Regex("""\s*\\([cv])\s*\d+\s*""")

    // Returns the string with \v markers removed at beginning of chunk.
    fun stripInitialMarkers(text: String): String {
        var newText = text
        var marker = stripCvRe.find(newText)
        while (marker != null) {
            newText = newText.removeRange(marker.range)
            marker = stripCvRe.find(newText)
        }
        return newText
    }

    // Returns true if the string contains all the verse numbers in verse range
// and there are no \v tags
    fun fitsInorPattern(str: String, verseRange: List<String>): Boolean {
        var fits = !str.contains("\\v")
        if (fits) {
            for (v in verseRange) {
                if (v !in str) {
                    fits = false
                    break
                }
            }
        }
        return fits
    }

    // Fixes a common error in Inor translations where the verse markers are listed at the beginning of the
// chunk but are empty, immediately followed by the first verse, followed by the next verse number and
// verse, followed by the next verse number and verse, and so on.
    fun fixInorMarkers(text: String, verseRange: List<String>): String {
        var saveChapterMarker = ""
        val chapMarkerRe = Regex("""\\c +[\d]{1,3}""")

        val c = chapMarkerRe.find(text)
        if (c != null) {
            saveChapterMarker = text.substring(c.range)
        }
        var str = stripInitialMarkers(text)
        if (!str.startsWith(verseRange[0])) {
            str = "${verseRange[0]} $str"
        }
        if (fitsInorPattern(str, verseRange)) {
            for (v in verseRange) {
                val pos = str.indexOf(v)
                str = if (pos == 0) {
                    "\\v $str"
                } else {
                    "${str.substring(0, pos)}\n\\v ${str.substring(pos)}"
                }
            }
            if (saveChapterMarker.isNotEmpty()) {
                str = "$saveChapterMarker\n$str"
            }
            var found = Regex("""[^0-9]\.""").find(str)
            while (found != null) {
                val pos = found.range.last
                str = "${str.substring(0, pos)} ${str.substring(pos)}"
                found = Regex("""[^0-9]\.""").find(str, pos + 1)
            }
        } else {
            str = text
        }
        return str
    }

    // Reads all lines from the specified file and converts the text to a single
// USFM section by adding chapter label, section marker, and paragraph marker where needed.
// Starts each USFM marker on a new line.
// Fixes white space, such as converting tabs to spaces and removing trailing spaces.
    fun convertFile(txtPath: String, chapterTitle: String?): String {
        val lines = File(txtPath).readLines()
        var section = "\n${combineLines(lines)}"    // fixes white space
        section = augmentChapter(section, chapterTitle)
        // section = fixPunctuationSpacing(section)
        // section = fixChapterMarkers(section)
        return section
    }

    // Returns true if the specified directory is one with text files to be converted
    fun isChapter(dirname: String): Boolean {
        return dirname != "00" && Regex("""\d{2,3}""").matches(dirname)
    }

    // Parses all manifest.json files in the current folder.
// If more than one manifest.json, their names vary.
// Return upper case bookId, or empty string if failed to retrieve.
// Also parses translator names out of the manifest, adds to global contributors list.
    fun parseManifest(path: String): String {
        var bookId = ""
        try {
            val manifest = loadJson(path)
            bookId = manifest["project"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                ?: throw Exception("Error parsing manifest")

            manifest["translators"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.forEach { contributors.add(it) }
        } catch (e: Exception) {
            throw e // TODO: Handle parsing error
        }
        return bookId
    }

    // Parses all manifest.json files in the current folder.
// If more than one manifest.json, their names vary.
// Return upper case bookId, or empty string if failed to retrieve.
// Also parses translator names out of the manifest, adds to global contributors list.
    fun getBookId(folder: String): String {
        var bookId = ""
        File(folder).listFiles()?.forEach { file ->
            if (file.name.contains("manifest") && file.name.contains(".json")) {
                bookId = parseManifest(file.absolutePath)
            }
        }
        if (bookId.isEmpty()) {
            Regex("""${languageCode}_([a-zA-Z1-3][a-zA-Z][a-zA-Z])_""").find(File(folder).name)?.let {
                bookId = it.groupValues[1].lowercase()
            }
        }
        return bookId
    }

    // Locates title.txt in either the front folder or 00 folder.
// Extracts the first line of that file as the book title.
    fun getBookTitle(): String {
        var bookTitle = ""
        var f = File(inputDir).resolve("front").resolve("title.txt")
        if (!f.exists()) {
//            f = File("00", "title.txt").absolutePath
        }
        if (f.exists()) {
            bookTitle = f.readLines().firstOrNull()?.trim() ?: ""
        } else {
            println("$f doesn't exist.")
        }

        return bookTitle
    }

    fun getChapterTitle(folder: String, chap: String): String {
        val titleFile = File(folder, "$chap/title.txt")
        return if (titleFile.exists()) {
            titleFile.readText()
        } else {
            ""
        }
    }

    fun listChapters(bookdir: File): List<File> {
        val chapters = mutableListOf<File>()
        bookdir.listFiles()?.forEach {
            if (it.isDirectory && isChapter(it.name)) {
                chapters.add(it)
            }
        }
        if (chapters.size > 99) {
            chapters.sortBy { it.name.toInt() }
        }
        return chapters
    }

    fun listChunks(chap: File): List<String> {
        val chunks = mutableListOf<String>()
        var longest = 0
        chap.listFiles()?.sortedBy { it.name }?.forEach { filename ->
            val chunky = Regex("""(\d{2,3})\.txt""").find(filename.name)
            if (chunky != null && filename.name != "00.txt") {
                val chunk = chunky.groupValues[1]
                chunks.add(chunk)
                if (chunk.length > longest) {
                    longest = chunk.length
                }
            }
        }
        if (longest > 2) {
            chunks.sortBy { it.toInt() }
        }
        return chunks
    }

    fun makeVerseRange(chunks: List<String>, i: Int, bookId: String, chapter: Int): List<String> {
        val verseRange = mutableListOf(chunks[i].removePrefix("0"))
        val limit = if (i + 1 < chunks.size) {
            chunks[i + 1].toInt()
        } else {
            usfmVerses[bookId.uppercase()]!!.verses[chapter - 1] + 1
        }
        var v = chunks[i].toInt() + 1
        while (v < limit) {
            verseRange.add(v.toString())
            v++
        }
        return verseRange
    }


    // Write the chapter titles to the specified file
//fun dumpChapterTitles(titles: List<String>, path: String) {
//    File(path).bufferedWriter().use { out ->
//        titles.forEach { title ->
//            if (!title.isNullOrEmpty()) {
//                out.write("$title\n")
//            }
//        }
//    }
//}
    fun dumpContributors() {
        val contributorsSet = contributors.toSet()
        val contributorsList = contributorsSet.toList().sorted()

        val path = File(targetDir, "contributors.txt")
        path.bufferedWriter().use { writer ->
            contributorsList.forEach { name ->
                writer.write("    - \"$name\"\n")
            }
        }
    }

    // Appends information about the current book to the global projects list.
    fun appendToProjects(bookId: String, bookTitle: String) {
        val testament = if (usfmVerses[bookId.uppercase()]!!.sort < 40) "nt" else "ot"
        val project = mapOf(
            "title" to bookTitle,
            "id" to bookId.lowercase(),
            "sort" to usfmVerses[bookId.uppercase()]?.sort,
            "path" to "./${makeUsfmFilename(bookId)}",
            "category" to "[ 'bible-$testament' ]"
        )
        projects.add(project)
    }

    fun dumpProjects() {
//    projects.sortBy { it["sort"] as Int } // TODO

        val path = makeManifestPath()
        val manifest = File(path).bufferedWriter()

        manifest.write("projects:\n")
        projects.map { it as RCProject }.forEach { p ->
            manifest.write("  -\n")
            val title = p.title
            val id = p.identifier
            val sort = p.sort
            val projectPath = p.path
            val category = p.categories.joinToString(", ")
            manifest.write("    title: \"$title\"\n")
            manifest.write("    versification: ufw\n")
            manifest.write("    identifier: \"$id\"\n")
            manifest.write("    sort: $sort\n")
            manifest.write("    path: \"$projectPath\"\n")
            manifest.write("    categories: [ $category ]\n")
        }

        manifest.close()
    }

    fun writeHeader(writer: BufferedWriter, bookId: String, bookTitle: String) {
        val content = """
            \id ${bookId.uppercase()}
            \ide UTF-8
            \h $bookTitle
            \toc1 $bookTitle
            \toc2 $bookTitle
            \toc3 ${bookId.lowercase()}
            \mt $bookTitle
        """.trimIndent() + "\n"

        writer.write(content)
    }

    fun convertBook(path: String, bookId: String, bookTitle: String) {
        val bookDir = File(path)
        val chapters = listChapters(bookDir)
        val usfmPath = File(targetDir, makeUsfmFilename(bookId))

        usfmPath.bufferedWriter().use { usfmWriter ->
            writeHeader(usfmWriter, bookId, bookTitle)

            for (chap in chapters) {
                val chapterTitle = getChapterTitle(path, chap.name)
                val chunks = listChunks(chap)
                var i = 0
                while (i < chunks.size) {
                    val filename = "${chunks[i]}.txt"
                    val txtPath = File(chap, filename).absolutePath
                    cleanupChunk(chap, filename, makeVerseRange(chunks, i, bookId, chap.name.toInt()))
                    val section = convertFile(txtPath, chapterTitle) + '\n'
                    usfmWriter.write(section)
                    i++
                }
            }

        }

        //        val titlesPath = usfmPath.absolutePath.replace(".usfm", "-chapters.txt")
        // dumpChapterTitles(titles, titlesPath)
    }

    fun convertFolder(folder: String, outputDir: String) {
        inputDir = folder
        targetDir = outputDir
        try {
            File(inputDir)
                .walk()
                .filter {
                    !it.path.contains(".git") &&
                        it.isDirectory &&
                            isBookFolder(it.absolutePath)
                }
                .forEach { file ->
                    println("Converting: ${file.absolutePath}\n")
                    val bookId = getBookId(file.absolutePath)
                    val bookTitle = getBookTitle()
                    if (bookId.isNotEmpty() && bookTitle.isNotEmpty()) {
                        convertBook(file.absolutePath, bookId, bookTitle)
                        appendToProjects(bookId, bookTitle)
                    } else {
                        if (bookId.isEmpty()) {
                            println("Unable to determine book ID in ${file.absolutePath}\n")
                        }
                        if (bookTitle.isEmpty()) {
                            println("Unable to determine book title in ${file.absolutePath}\n")
                        }
                    }
                }
        } catch (e: Exception) {
            throw e
        }
    }

    fun makeUsfmFilename(bookId: String): String {
        val bookSlug = bookId.uppercase()
        return if (usfmVerses.isNotEmpty()) {
            val num = usfmVerses[bookSlug]?.usfmNumber ?: ""
            "$num-$bookSlug.usfm"
        } else {
            val pathComponents = File("").absolutePath.split(File.separator)
            "${pathComponents.last()}.usfm"
        }
    }

    // Returns path of temporary manifest file block listing projects converted
    fun makeManifestPath(): String {
        return File(targetDir).resolve("projects.yaml").toString()
    }

    fun convert(dir: String, outputDir: String) {
        targetDir = outputDir
        File(targetDir).mkdir()
        File(makeManifestPath()).delete()

        if (isBookFolder(dir)) {
            convertFolder(dir, outputDir)
        } else {
            File(dir).listFiles()?.forEach { folder ->
                if (isBookFolder(folder.absolutePath)) {
                    convertFolder(folder.absolutePath, outputDir)
                }
            }
        }
        dumpContributors()
        dumpProjects()
    }

    companion object {

        fun getVersification(): Map<String, BookVersification> {
            val stream = TextToUSFM::class.java.classLoader.getResourceAsStream("verse_counts.json")
            stream.use {
                return JSON.decodeFromString(
                    MapSerializer(String.serializer(), BookVersification.serializer()),
                    it.readBytes().decodeToString()
                )
            }
        }
    }
}