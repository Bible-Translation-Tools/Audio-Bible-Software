package org.bibletranslationtools.otter.common.domain.versification

/**
 * The Copenhagen Alliance standard versifications bundled with the app, by their standard codes.
 *
 * A source's declared versification is not trusted to describe its text: every bundled source
 * declares `ufw`, whatever numbering it actually follows. Source import instead compares the
 * text's verse counts with these and uses the closest ([VersificationDetector]).
 */
object StandardVersifications {
    /** English (KJV-style) numbering. Also the fallback when nothing better is known. */
    const val ENG = "eng"

    /** Russian Synodal numbering. */
    const val RSC = "rsc"

    /** Original-language numbering; the common reference point the others map to. */
    const val ORG = "org"

    /** In preference order: when two fit a text equally well, the earlier one wins. */
    val all = listOf(ENG, RSC, ORG)

    const val DEFAULT = ENG

    /** Where each file lives in the bundled resources. */
    fun resourcePath(code: String) = "files/versification/copenhagen/$code.json"
}
