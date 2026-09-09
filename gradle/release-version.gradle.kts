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
// versionCode must be a POSITIVE integer — AGP rejects 0. Coerce so a caller passing 0 (e.g. a CI
// smoke test with -PappVersionCode=0, or a v0.0.0 tag) still builds rather than failing config.
val versionCodeInt = ((findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1).coerceAtLeast(1)

// Desktop installers are stricter than Android about the version string: an MSI ProductVersion and a
// DMG CFBundleVersion must be a purely numeric major.minor.patch, with no `-beta.1` prerelease or
// `+build` metadata — jpackage rejects those outright. So the desktop package version is the numeric
// core only (the prerelease/build parts live in the tag and the release name, not the binary), padded
// to three components.
val desktopVersion = versionName
    .substringBefore('-')
    .substringBefore('+')
    .split('.')
    .let { parts ->
        val nums = (0..2).map { parts.getOrNull(it)?.toIntOrNull() ?: 0 }.toMutableList()
        // jpackage refuses a macOS app-version whose FIRST number is 0 ("cannot be zero or
        // negative"), so a 0.x tag — or the 0.0.0 CI smoke version — cannot be packaged as written.
        // Bump the major to 1 for the desktop installer ONLY; Android keeps the true version. Moot
        // for any 1.x release, which is the norm here.
        nums[0] = nums[0].coerceAtLeast(1)
        nums.joinToString(".")
    }

// :app-recorder ships the legacy Android BTT-Recorder's applicationId, so Android treats it as an
// upgrade of that app and its versionCode has to exceed the last one the legacy app published (56,
// per that project's build.gradle). :app-orature has no such history and keeps the plain value,
// hence a separate derived number rather than a floor applied to both.
//
// An offset rather than a coerce: versionCode is derived as major*1e6 + minor*1e3 + patch, so a 1.x
// release is already far above the legacy ceiling, while a 0.0.x tag or a local build yields 1.
// Coercing those to 57 would give different releases the same versionCode, which Play rejects and
// which breaks in-place upgrades. Adding the ceiling keeps the sequence monotonic in the tag.
val legacyRecorderVersionCode = 56
val recorderVersionCodeValue = versionCodeInt + legacyRecorderVersionCode

extra["appVersionName"] = versionName
extra["appVersionCode"] = versionCodeInt
extra["recorderVersionCode"] = recorderVersionCodeValue
extra["desktopPackageVersion"] = desktopVersion
