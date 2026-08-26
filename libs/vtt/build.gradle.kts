// :libs:vtt — was org.bibletranslationtools:kotlin-vtt:1.0.0.
// WebVTT cue parsing/writing, used for audio marker metadata.
//
// Package stays org.bibletranslationtools.vtt — :shared and :libs:scripture-alignment both
// import it directly.
plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
}

kotlin {
    // A real JDK 11 toolchain rather than `compilerOptions.jvmTarget` + a
    // `java { sourceCompatibility }` pair. Those two disagree about the API surface: the java
    // block makes KGP compile Kotlin against JDK 11's *documented* API, which drops methods
    // these libraries genuinely call (String.strip() in :libs:vtt), while omitting it leaves
    // compileJava at 21 and KGP fails the build on the mismatch. A toolchain sets both
    // consistently against a JDK that really does have those methods.
    //
    // 11 because these modules are packaged into the Android apps, whose compileOptions cap
    // bytecode at 11.
    jvmToolchain(11)
}

dependencies {
    // ParsableByteArray uses com.google.common.primitives.{Chars, UnsignedBytes} and nothing else
    // from Guava. The catalog pins the -android variant deliberately — see libs.versions.toml.
    // Two primitive helpers is a thin reason to ship Guava at all; replacing them with hand-rolled
    // equivalents would drop the dependency entirely, but that is a source change, not a port.
    implementation(libs.guava)
}
