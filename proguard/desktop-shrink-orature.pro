# :app-orature additions to proguard/desktop-shrink.pro.

# Sentry (crash reporting). Sentry.init discovers integrations, transports and
# option classes reflectively and reads external configuration by class name, so
# the shrinker cannot see most of it. It only activates when a sentry.properties
# with a DSN is on the classpath (SentryCrashReporter.fromClasspath), but the
# classes must survive either way.
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# Orature launches external audio plugins as OS processes (PluginLauncher.desktop.kt).
# That path is plain ProcessBuilder with no reflection, so it needs no keeps —
# noted here so the absence is deliberate rather than an oversight.
