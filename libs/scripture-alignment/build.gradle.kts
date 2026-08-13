// :libs:scripture-alignment — was org.bibletranslationtools:kotlin-scripture-alignment:1.0.0
// (vendored source is 1.3.2-demo). Alignment records between scripture text and audio.
//
// Package stays org.bibletranslationtools.kotlinscripturealignment — :shared imports it directly.
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
    // Sibling dependency: this library calls org.bibletranslationtools.vtt. The original build
    // pointed at project(":common:kotlin-vtt") from its own repo layout; here it goes by
    // coordinate so the root build's substitution can redirect it to :libs:vtt, which also keeps
    // it working whichever order the two are vendored in.
    implementation("org.bibletranslationtools:kotlin-vtt:1.0.0")

    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // Dropped from the original build: org.slf4j:slf4j-api:2.0.13 is declared but unreferenced.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    // The tests are JUnit 5 (org.junit.jupiter.api), unlike resource-container's and
    // scripture-burrito's, which are JUnit 4.
    useJUnitPlatform()
    // The original build also redirected test output to a file and pinned maxParallelForks,
    // via a ${project.rootDir}/src/test/resources/logging.properties that does not exist in this
    // repo's layout. Left out rather than ported to a path that would silently not resolve.
}
