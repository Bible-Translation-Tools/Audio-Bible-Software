// :libs:resource-container — was org.wycliffeassociates:kotlin-resource-container:0.12.0.
// Reads and writes Resource Containers. ResourceContainer.getAccessor() is on the path of every
// import, which makes this the most load-bearing of the vendored libraries.
//
// Package stays org.wycliffeassociates.resourcecontainer — :shared imports it directly.
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
    // Jackson version comes from the catalog rather than the 2.15.1 the original build named:
    // the root build forces 2.14.3 project-wide because 2.15+ cannot run on Android 7.
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)

    // No tika-core. Its only use here was a private detectFileType() comparing against
    // MediaType.APPLICATION_ZIP; ResourceContainer.isZipFile() now reads the four-byte local file
    // header instead. Keeping it would also have put the pristine jar on the Android classpath
    // beside the repackaged tika-core-android from :shared, which fails checkDuplicateClasses.
    //
    // Dropped from the original build: org.json:json:20180813 was declared but no source file
    // references it.
    testImplementation(libs.junit)
}
