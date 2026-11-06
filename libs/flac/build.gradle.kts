// :libs:flac — Project Nayuki's FLAC library (Java), vendored from source. It isn't published to
// any Maven repository, and publishing needs changes an upstream jar wouldn't have. See README.md.
//
// LGPL-3.0-or-later (COPYING.txt, COPYING.LESSER.txt).
plugins {
    `java-library`
}

java {
    // 11 because this is packaged into the Android apps, whose compileOptions cap bytecode at 11 —
    // the same reason the Kotlin libs/ modules use jvmToolchain(11).
    toolchain {
        languageVersion = JavaLanguageVersion.of(11)
    }
}
