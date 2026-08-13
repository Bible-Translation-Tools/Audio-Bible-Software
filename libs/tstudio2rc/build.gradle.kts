// :libs:tstudio2rc — was org.wycliffeassociates:kotlin-tstudio2rc:1.0.2 (vendored source is
// 1.0.3). Converts translationStudio projects into Resource Containers.
//
// Package stays org.wycliffeassociates.tstudio2rc — :shared imports it directly.
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
    // Sibling dependency: this library calls org.wycliffeassociates.resourcecontainer. Declared by
    // coordinate rather than as projects.libs.resourceContainer on purpose — the substitution
    // block in the root build redirects it to :libs:resource-container, and keeps working whichever
    // order the two are vendored in.
    implementation("org.wycliffeassociates:kotlin-resource-container:0.12.0")

    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)
    // The only consumer of jackson-dataformat-csv anywhere in the build.
    implementation(libs.jackson.dataformat.csv)

    // The original build declared useJUnitPlatform() with kotlin-test, so the JUnit 5 flavour.
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
