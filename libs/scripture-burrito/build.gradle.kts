// :libs:scripture-burrito — was org.bibletranslationtools:kotlin-scripture-burrito:1.0.1
// (the vendored source is 1.0.2). Scripture Burrito container reading/writing.
// BurritoContainer.getAccessor() mirrors ResourceContainer.getAccessor(), including the same
// Tika-for-zip-detection pattern.
//
// Package stays org.bibletranslationtools.scriptureburrito — :shared imports it directly.
plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
    alias(libs.plugins.kotlinSerialization)
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
    // `api`, not `implementation`, matching the original build — Jackson types appear in this
    // library's public surface, so consumers need them on the compile classpath.
    // api, not implementation: MetadataSchema and friends are this library's public surface, so
    // consumers building a burrito need the serialization annotations and Json type on their
    // compile classpath the way they needed Jackson's before.
    api(libs.kotlinx.serialization.json)
    // No tika-core. Its only use here was a private detectFileType() comparing against
    // MediaType.APPLICATION_ZIP; BurritoContainer.isZipFile() now reads the four-byte local file
    // header instead, matching :libs:resource-container. With both libraries off it, nothing in
    // the build needs Tika and :shared no longer has to repackage it for D8 at minSdk 24.

    // Dropped from the original build, neither is referenced by any source file here:
    //   org.slf4j:slf4j-api:2.0.13
    //   org.json:json:20180813
    testImplementation(libs.junit)
}
