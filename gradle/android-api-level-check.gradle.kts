// Fails the build when OUR code calls a java.* / javax.* API newer than minSdk.
//
// These are the bugs it exists for, all of which shipped and all of which threw NoSuchMethodError
// on an Android 7 tablet:
//
//   InputStream.transferTo        API 33   (12 call sites)
//   InputStream.readAllBytes      API 33   (on opening a book)
//   Enumeration.asIterator        API 33
//   Executable.getParameterCount  API 26   (inside jackson-databind)
//
// Every one is a Java 8/9 method Android only added later. They compile without a murmur, dex
// without a murmur, and fail the moment the line runs — so short of executing that exact code path
// on an old device, nothing finds them.
//
// WHY NOT LINT. `NewApi` is precisely this check and would be the right tool. It does not work
// here: AGP lint analyses no Kotlin source at all in these Kotlin Multiplatform modules.
// :shared:lintDebug, with three deliberate API-33 calls planted in commonMain and then again in
// androidMain, reported 64 warnings — every one about Gradle files (GradleDependency,
// UseTomlInstead, …) and not one about source. Re-test that before deleting this task: if lint ever
// starts seeing these source sets it is strictly better than this, and this should go.
//
// WHY BYTECODE, AND WHY ONLY OURS. This reads compiled classes rather than the dex. A dex scan of
// the Orature APK reports 269 hits, nearly all java/time/*: core library desugaring rewrites OUR
// references to j$/time/*, so what remains is the desugar library's own conversion plumbing, which
// is API-level guarded and fine. Telling those apart needs the CALLER of each reference, which the
// dex method table does not record. Reading our own class files sidesteps that, and asks the
// question actually worth asking: does code we can fix call something too new? Third-party
// dependencies are out of scope on purpose — we cannot fix them, most are covered by desugaring,
// and jackson-databind's API-26 call was dealt with by removing Jackson, not by a lint rule.
//
// DESUGARING. Types the coreLibraryDesugaring artifact backports are exempt: java.time,
// java.nio.file, java.util.stream and friends are available on API 24 once rewritten. The exempt
// set is read out of the desugar jar rather than hardcoded, so bumping the artifact updates it.
//
// Two shapes this file must keep. Everything lives INSIDE the task because a .gradle.kts script
// plugin compiles to a class, so a top-level `const val` is not really top-level and the script
// silently fails to apply. And these comments are `//` rather than a leading /** */ block, because
// a KDoc header at the top of an applied script makes Gradle skip its contents — the task simply
// never registers, with no error anywhere.

tasks.register("checkAndroidApiLevel") {
    group = "verification"
    description = "Fails if our code calls a java.*/javax.* API newer than minSdk."

    // It reads compiled classes, so it must not run against a stale build directory — a scan of
    // last build's output is worse than no scan, because it reports green on code it never saw.
    dependsOn("compileDebugSources")
    rootProject.file("libs").listFiles().orEmpty()
        .filter { File(it, "build.gradle.kts").isFile }
        .forEach { dependsOn(":libs:${it.name}:classes") }

    val minSdk = rootProject.extensions.getByType<VersionCatalogsExtension>()
        .named("libs").findVersion("android-minSdk").get().requiredVersion.toInt()
    val allowlistFile = rootProject.file("gradle/android-api-level-allowlist.txt")
    val desugarJars = configurations.findByName("coreLibraryDesugaring")
    // Resolved separately: the runtime artifact carries the backported CLASSES, this one carries the
    // spec describing retargeted METHODS. Detached so it never joins any real classpath.
    val desugarSpecJars = configurations.detachedConfiguration(
        rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
            .findLibrary("desugar-jdk-libs-configuration-nio").get().get()
    )
    val libsDir = rootProject.file("libs")
    val moduleClasses = layout.buildDirectory.dir("tmp/kotlin-classes/debug")
    val projectDir = rootProject.projectDir
    val localProperties = rootProject.file("local.properties")

    doLast {
        // ── the API database ───────────────────────────────────────────────────────────
        // api-versions.xml is the only reliable source for @since: the android.jar stubs do not
        // carry it.
        val sdkDir = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: localProperties.takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter('=')
            ?: throw GradleException("ANDROID_HOME is not set and local.properties has no sdk.dir")

        val apiXml = File(sdkDir, "platforms").listFiles().orEmpty()
            .mapNotNull { File(it, "data/api-versions.xml").takeIf { f -> f.isFile } }
            .maxByOrNull { it.parentFile.parentFile.name }
            ?: throw GradleException("No api-versions.xml under $sdkDir/platforms — install a platform SDK")

        fun level(v: Any?, fallback: Int): Int =
            v?.toString()?.substringBefore('.')?.toIntOrNull() ?: fallback

        val classSince = HashMap<String, Int>()
        val memberSince = HashMap<String, Int>()   // "owner.name+descriptor"
        val doc = groovy.xml.XmlParser(false, false).parse(apiXml)
        @Suppress("UNCHECKED_CAST")
        for (c in doc.children() as List<groovy.util.Node>) {
            val owner = c.attribute("name")?.toString() ?: continue
            val since = level(c.attribute("since"), 1)
            classSince[owner] = since
            for (m in c.children() as List<groovy.util.Node>) {
                if (m.name().toString() != "method") continue
                val member = m.attribute("name")?.toString() ?: continue
                memberSince["$owner.$member"] = level(m.attribute("since"), since)
            }
        }

        // ── what desugaring covers ─────────────────────────────────────────────────────
        val desugared = desugarJars
            ?.let { runCatching { it.resolve() }.getOrDefault(emptySet()) }.orEmpty()
            .flatMap { jar ->
                runCatching {
                    java.util.zip.ZipFile(jar).use { zip ->
                        zip.entries().asSequence()
                            .map { it.name }
                            // The artifact ships the backported types under their real java/ names;
                            // D8 renames them to j$/ while dexing. Matching on j$/ finds nothing.
                            .filter { it.startsWith("java/") && it.endsWith(".class") }
                            .map { it.removeSuffix(".class") }
                            .toList()
                    }
                }.getOrDefault(emptyList())
            }.toSet()

        // Both exemptions below describe what core library desugaring provides, so they only hold
        // for a module that actually has it. Without that dependency the same call really is a
        // NoSuchMethodError, and must be reported.
        val desugaringEnabled = desugared.isNotEmpty()

        // Methods D8 RETARGETS to its own implementations. These live on classes that are not
        // themselves backported — java.io.File, java.util.Date — so the runtime artifact says nothing
        // about them, and without this they read as three real API-26 violations when they are safe.
        // Keys look like "a$Path i$File#toPath()", where the prefixes are package codes.
        val retargeted = runCatching {
            val spec = desugarSpecJars.resolve().firstNotNullOfOrNull { jar ->
                java.util.zip.ZipFile(jar).use { zip ->
                    zip.getEntry("META-INF/desugar/d8/desugar.json")
                        ?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                }
            } ?: return@runCatching emptySet<String>()

            val json = groovy.json.JsonSlurper().parseText(spec) as Map<*, *>
            val packageOf = (json["package_map"] as Map<*, *>)
                .entries.associate { (k, v) -> v.toString() to k.toString() }

            fun ownerAndName(key: String): String? {
                // "<return> <code><Class>#<method>(<args>)" — only the receiver and method matter.
                val receiver = key.substringAfter(' ', "").substringBefore('#').ifEmpty { return null }
                val method = key.substringAfter('#', "").substringBefore('(').ifEmpty { return null }
                val code = receiver.take(2)
                val pkg = packageOf[code]?.removePrefix("j\$.") ?: return null
                return "${pkg.replace('.', '/')}/${receiver.drop(2)}.$method"
            }

            // Each *_flags section is a LIST of flag objects, one per API-level band, not a map.
            buildSet {
                for (section in listOf("common_flags", "program_flags", "library_flags")) {
                    val bands = json[section] as? List<*> ?: continue
                    for (band in bands) {
                        val flags = band as? Map<*, *> ?: continue
                        for ((name, value) in flags) {
                            if (!name.toString().contains("retarget")) continue
                            val keys = (value as? Map<*, *>)?.keys ?: continue
                            keys.forEach { k -> ownerAndName(k.toString())?.let { add(it) } }
                        }
                    }
                }
            }
        }.getOrDefault(emptySet()).takeIf { desugaringEnabled }.orEmpty()

        val allowed = allowlistFile.takeIf { it.exists() }?.readLines().orEmpty()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

        // ── class file constant pools ──────────────────────────────────────────────────
        // Only the constant pool is read: every method a class calls appears there as a Methodref
        // or InterfaceMethodref, which is all this needs and far less work than decoding code
        // attributes.
        fun methodRefsIn(bytes: ByteArray): Pair<String, List<String>> {
            val input = java.io.DataInputStream(bytes.inputStream())
            input.use {
                if (it.readInt() != -0x35014542) return "?" to emptyList()   // 0xCAFEBABE
                it.readUnsignedShort(); it.readUnsignedShort()               // minor, major
                val count = it.readUnsignedShort()

                val utf8 = HashMap<Int, String>()
                val classNameIdx = HashMap<Int, Int>()
                val nameAndType = HashMap<Int, Pair<Int, Int>>()
                val refs = ArrayList<Pair<Int, Int>>()

                var i = 1
                while (i < count) {
                    when (it.readUnsignedByte()) {
                        1 -> utf8[i] = it.readUTF()                                   // Utf8
                        7 -> classNameIdx[i] = it.readUnsignedShort()                 // Class
                        10, 11 -> refs += it.readUnsignedShort() to it.readUnsignedShort()
                        9 -> { it.readUnsignedShort(); it.readUnsignedShort() }       // Fieldref
                        12 -> nameAndType[i] = it.readUnsignedShort() to it.readUnsignedShort()
                        8, 16, 19, 20 -> it.readUnsignedShort()                       // String/MethodType/Module/Package
                        3, 4 -> it.readInt()                                          // Integer/Float
                        15 -> { it.readUnsignedByte(); it.readUnsignedShort() }        // MethodHandle
                        17, 18 -> { it.readUnsignedShort(); it.readUnsignedShort() }   // Dynamic/InvokeDynamic
                        // Longs and doubles take TWO constant pool slots. Forgetting that
                        // desynchronises the rest of the pool.
                        5, 6 -> { it.readLong(); i++ }
                        else -> return "?" to emptyList()
                    }
                    i++
                }

                it.readUnsignedShort()                                                 // access flags
                val self = classNameIdx[it.readUnsignedShort()]?.let { n -> utf8[n] } ?: "?"

                val out = refs.mapNotNull { (cls, nat) ->
                    val owner = classNameIdx[cls]?.let { n -> utf8[n] } ?: return@mapNotNull null
                    val (nameIdx, descIdx) = nameAndType[nat] ?: return@mapNotNull null
                    val name = utf8[nameIdx] ?: return@mapNotNull null
                    val desc = utf8[descIdx] ?: return@mapNotNull null
                    "$owner.$name$desc"
                }
                return self to out
            }
        }

        // ── scan ───────────────────────────────────────────────────────────────────────
        // Only what we build: this module's Android classes (which is where commonMain lands, the
        // very source set lint cannot see) plus the vendored libraries.
        val roots = buildList {
            add(moduleClasses.get().asFile)
            libsDir.listFiles().orEmpty().forEach { add(File(it, "build/classes/kotlin/main")) }
        }.filter { it.isDirectory }

        if (roots.isEmpty()) {
            throw GradleException(
                "checkAndroidApiLevel found nothing to scan. It reads compiled classes, so the " +
                    "Android variant must be built first — run assembleDebug before this."
            )
        }

        val callersOf = HashMap<String, MutableSet<String>>()
        var scanned = 0
        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { f ->
                scanned++
                val (self, refs) = runCatching { methodRefsIn(f.readBytes()) }.getOrNull() ?: return@forEach
                for (ref in refs) {
                    if (!ref.startsWith("java/") && !ref.startsWith("javax/")) continue
                    if (ref in allowed) continue
                    val owner = ref.substringBeforeLast('.')
                    if (owner in desugared) continue
                    // Descriptors are dropped for retarget matching: the spec spells types in its own
                    // shorthand, and owner+name is unambiguous enough for the handful it covers.
                    if ("${ref.substringBeforeLast('(')}" in retargeted) continue
                    val since = memberSince[ref] ?: continue
                    if (since <= minSdk) continue
                    callersOf.getOrPut("$since|$ref") { linkedSetOf() } += self
                }
            }
        }

        logger.lifecycle(
            "checkAndroidApiLevel: $scanned classes scanned, minSdk $minSdk, " +
                "${desugared.size} desugared types + ${retargeted.size} retargeted methods exempt, " +
                "${allowed.size} allowlisted"
        )

        if (callersOf.isNotEmpty()) {
            val report = callersOf.entries
                .sortedWith(compareByDescending<Map.Entry<String, MutableSet<String>>> {
                    it.key.substringBefore('|').toInt()
                }.thenBy { it.key })
                .joinToString("\n\n") { (key, callers) ->
                    val since = key.substringBefore('|')
                    "  API $since  ${key.substringAfter('|')}\n" +
                        callers.sorted().joinToString("\n") { "        called from $it" }
                }
            throw GradleException(
                "\n${callersOf.size} call(s) to APIs newer than minSdk $minSdk:\n\n$report\n\n" +
                    "These compile and dex cleanly, then throw NoSuchMethodError at runtime on older\n" +
                    "devices. Use an equivalent that exists at minSdk — Kotlin's stdlib usually has one:\n" +
                    "  transferTo   -> copyTo\n" +
                    "  readAllBytes -> readBytes\n" +
                    "  asIterator   -> asSequence\n" +
                    "or guard the call with Build.VERSION.SDK_INT and add it to\n" +
                    "${allowlistFile.relativeTo(projectDir)}, with a note saying which.\n"
            )
        }
    }
}
