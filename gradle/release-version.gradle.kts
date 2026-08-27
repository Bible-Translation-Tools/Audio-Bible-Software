// Derives the app version from properties the release workflow injects from the git tag, exposing
// three values via `extra` for the module that applies this. Applied by :app-recorder and
// :app-orature; both read the same names.
//
//   -PappVersion       the human version, e.g. "1.2.3" or "1.2.3-beta.1" (from the tag)
//   -PappVersionCode   the Android versionCode, an integer (derived in the workflow)
//
// Both have fallbacks, so a local build with no properties keeps the historical 1.0 / 1 and nothing
// about day-to-day development changes.

val versionName = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0"
val versionCodeInt = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

// Desktop installers are stricter than Android about the version string: an MSI ProductVersion and a
// DMG CFBundleVersion must be a purely numeric major.minor.patch, with no `-beta.1` prerelease or
// `+build` metadata — jpackage rejects those outright. So the desktop package version is the numeric
// core only (the prerelease/build parts live in the tag and the release name, not the binary), padded
// to three components.
val desktopVersion = versionName
    .substringBefore('-')
    .substringBefore('+')
    .split('.')
    .let { parts -> (0..2).joinToString(".") { parts.getOrNull(it)?.toIntOrNull()?.toString() ?: "0" } }

extra["appVersionName"] = versionName
extra["appVersionCode"] = versionCodeInt
extra["desktopPackageVersion"] = desktopVersion
