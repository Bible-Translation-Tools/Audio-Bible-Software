package org.bibletranslationtools.bttrecorder2.legacymigration

import java.io.InputStream

/**
 * Where the shipped [UlbTemplate] files are read from: the Android recorder's assets in the app,
 * the repo's assets folder in tests.
 */
interface IUlbTemplateFiles {

    /** Opens [template]'s resource container zip. The caller closes the stream. */
    fun open(template: UlbTemplate): InputStream
}
