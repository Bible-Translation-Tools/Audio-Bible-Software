# Shrink-only ProGuard configuration shared by the desktop (jpackage) release
# builds of :app-recorder and :app-orature. Wired in from each app's
# compose.desktop { application { buildTypes.release.proguard { ... } } }.
#
# WHY THIS EXISTS
# The packaged app/ directory is ~187 MB, and 36 MB of that is
# material-icons-extended, which ships all ~11,100 Material icons while the two
# apps between them reference about 105. That jar is ideally shaped for a
# shrinker: one class per icon (androidx/compose/material/icons/filled/AddKt)
# holding a lazy ImageVector getter, so unreferenced icons drop cleanly.
#
# WHY SHRINK ONLY
# This app resolves types reflectively in several places — jOOQ record mapping,
# Jackson (de)serialization of the resource-container/burrito models, snakeyaml,
# JNA, sqlite-jdbc, and ServiceLoader lookups. Nearly all of that breaks under
# *renaming* or *inlining*, not under dead-code removal, so obfuscation and
# optimization stay off and the reflective libraries below are kept whole.
#
# Anything reached only reflectively is invisible to the shrinker, so a missing
# -keep fails at RUNTIME with NoClassDefFoundError/ClassNotFoundException and
# never at build time. A green build proves nothing here.

# ---------------------------------------------------------------------------
# Mode: shrink only. (The Compose plugin also passes these when obfuscate/
# optimize are false in the DSL; repeated here so the file stands alone.)
# ---------------------------------------------------------------------------
-dontobfuscate
-dontoptimize

# Reflection reads these: annotations drive Jackson/Retrofit/jOOQ, Signature
# carries Kotlin generic types, InnerClasses/EnclosingMethod keep nested and
# companion references resolvable by name.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations
-keepattributes AnnotationDefault,MethodParameters,Record,PermittedSubclasses

# ServiceLoader provider files live in META-INF/services and are copied through
# untouched; the classes they name are only reachable by name, so keep them.
# (Derived by scanning every jar in the packaged app/ dir for
# META-INF/services/* — see the per-library keeps below, which cover all of:
#  com.fasterxml.jackson.core.JsonFactory, ...core.ObjectCodec,
#  ...databind.Module, java.sql.Driver -> org.sqlite.JDBC,
#  org.slf4j.spi.SLF4JServiceProvider -> org.slf4j.simple.SimpleServiceProvider,
#  org.apache.tika.detect.Detector, ...metadata.filter.MetadataFilter,
#  org.freedesktop.dbus.spi.transport.ITransportProvider,
#  plus kotlin.reflect.* and kotlinx.coroutines.swing.* which the Compose
#  default rules already keep.)

# ---------------------------------------------------------------------------
# Our own code and the Bible-translation domain libraries.
# These are Koin-wired, jOOQ-mapped and Jackson-bound throughout, and together
# they are only a couple of MB, so keep them whole rather than reason per class.
# ---------------------------------------------------------------------------
-keep class org.bibletranslationtools.** { *; }
-keep class org.wycliffeassociates.** { *; }
# usfmtools ships a `tangible` helper package alongside its own.
-keep class tangible.** { *; }

# ---------------------------------------------------------------------------
# Libraries whose class hierarchy must survive verbatim.
#
# ProGuard drops an entry from a class's `implements` list when the same
# interface is already reachable through another superinterface or the
# superclass — Job is removed from JobSupport because ChildJob and ParentJob
# both extend it. That is invisible to `instanceof`, which resolves interfaces
# transitively, but it is FATAL to a Kotlin default-method super call: those
# compile to `invokespecial` on an interface method, and the verifier rejects
# an invokespecial whose interface is not a DIRECT superinterface. The failure
# is java.lang.VerifyError ("interface method reference is in an indirect
# superinterface") the first time the class is loaded, which for coroutines is
# during startup, so the packaged app never draws a window.
#
# Keeping a class marks it used and stops the shrinker pruning it from the
# implements arrays. These are class-only keeps (no `{ *; }`): unused members
# still go, only the hierarchy is pinned.
#
# The affected classes were found by diffing the declared superclass/interface
# list of every class in the packaged app/ dir against the same class in the
# original artifact — 63 classes across the ten libraries below. Re-run that
# diff after a dependency bump; a green build and a working dev `run` both
# prove nothing, because ProGuard only runs for the release distributable.
# ---------------------------------------------------------------------------
-keep class kotlinx.coroutines.**
-keep class kotlinx.serialization.**
-keep class androidx.compose.animation.**
-keep class androidx.compose.foundation.**
-keep class androidx.compose.runtime.**
-keep class androidx.compose.ui.**
-keep class androidx.lifecycle.**
-keep class androidx.navigation.**
-keep class javazoom.jl.**
-keep class org.digitalmediaserver.cuelib.**

# ---------------------------------------------------------------------------
# Reflective / ServiceLoader-driven third-party libraries.
# ---------------------------------------------------------------------------
# jOOQ maps result records onto types by reflection.
-keep class org.jooq.** { *; }
# Jackson: factories and mappers come from META-INF/services; models are bound
# field-by-field, and dropping an "unused" getter silently truncates output.
-keep class com.fasterxml.jackson.** { *; }
# JNA binds native functions to interface methods by name.
-keep class com.sun.jna.** { *; }
# sqlite-jdbc: loaded via Class.forName("org.sqlite.JDBC") in AppDatabase, and
# it unpacks its own native library by resource path.
-keep class org.sqlite.** { *; }
# snakeyaml instantiates target types reflectively.
-keep class org.yaml.snakeyaml.** { *; }
# slf4j binds its provider through ServiceLoader (slf4j-simple here).
-keep class org.slf4j.** { *; }
# Tika detectors/filters come from META-INF/services.
-keep class org.apache.tika.** { *; }
# dbus-java is Linux-only dead weight elsewhere, but its transport provider is a
# ServiceLoader entry; keeping it costs little and avoids a platform-specific
# failure that would only show up on Linux.
-keep class org.freedesktop.dbus.** { *; }
# Retrofit builds dynamic proxies from annotated interfaces; okhttp/okio sit
# underneath it. All small.
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
# RxJava's plugin hooks and the assembly/schedulers machinery are reached
# indirectly; the playback engine leans on this heavily.
-keep class io.reactivex.** { *; }
-keep class com.jakewharton.rxrelay2.** { *; }
# Koin resolves definitions by KClass at runtime.
-keep class org.koin.** { *; }
# Compose resources reads packaged files by path at runtime.
-keep class org.jetbrains.compose.resources.** { *; }
# javax.inject / Dagger annotations referenced by generated code.
-keep class javax.inject.** { *; }
-keep class dagger.** { *; }

# ---------------------------------------------------------------------------
# Unresolved references to OPTIONAL dependencies that are absent — and unused —
# on desktop. Without these, ProGuard aborts with "Please correct the above
# warnings first" after ~1670 notes. Each entry is a library's optional
# integration path we never take.
# ---------------------------------------------------------------------------
-dontwarn javafx.**                  # legacy JavaFX interop
-dontwarn javax.annotation.**        # okhttp/retrofit nullability annotations
-dontwarn javax.persistence.**       # jOOQ JPA integration
-dontwarn javax.xml.bind.**
-dontwarn javax.activation.**
-dontwarn org.osgi.**                # Tika OSGi metadata
-dontwarn aQute.bnd.**
-dontwarn android.**                 # okhttp's Android-only code paths
-dontwarn com.android.**
-dontwarn dalvik.**
-dontwarn org.conscrypt.**           # okhttp optional TLS provider
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.sun.activation.**
-dontwarn org.glassfish.**
-dontwarn com.google.common.**       # Guava, optional for several libs
-dontwarn com.google.errorprone.**
-dontwarn com.google.protobuf.**
-dontwarn sun.misc.**                # Unsafe, probed via Class.forName
-dontwarn org.apache.xerces.**
-dontwarn org.apache.xml.**
-dontwarn org.w3c.dom.**
-dontwarn org.slf4j.impl.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.jvm.internal.**

# MethodHandle.invokeExact is signature-polymorphic: the JVM resolves it against
# the call site's descriptor, so ProGuard can't find a matching declaration and
# reports it as unresolved. Not a real missing reference.
-dontwarn org.apache.tika.io.MappedBufferCleaner

# The "keeps the entry point X, but not the descriptor class Y" notes are
# expected: our classes are kept wholesale by the wildcards above, while their
# parameter types (filekit, TarsosDSP, reactive-streams) are kept only where
# actually reachable. Nothing here is reached by reflection.
-dontnote org.bibletranslationtools.**
-dontnote org.jooq.**
-dontnote io.reactivex.**
-dontnote org.jetbrains.skiko.**
