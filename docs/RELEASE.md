# Releasing

Releases are cut by pushing a **version tag**. The `release` workflow
(`.github/workflows/release.yml`) builds, signs, publishes a GitHub Release, and syncs the artifacts
to S3.

## Tagging

| Tag | Builds | Release |
| --- | --- | --- |
| `v1.2.3` | **both** apps | one Release `v1.2.3` with every installer for both |
| `recorder-v1.2.3` | BTT-Recorder only | Release `recorder-v1.2.3` |
| `orature-v1.2.3` | Orature only | Release `orature-v1.2.3` |
| any `…-v1.2.3-beta.1` (a `-suffix`) | as above | marked **pre-release** |

```bash
git tag v1.2.3        # both apps
git tag recorder-v1.2.3-beta.1   # recorder, pre-release
git push origin v1.2.3
```

There is also a manual **workflow_dispatch** (Actions → release → Run workflow) taking `apps`
(both/recorder/orature) and a `version` string, for a build without tagging.

### What the version controls

The tag's version is injected into the build (`-PappVersion` / `-PappVersionCode`, wired in
`gradle/release-version.gradle.kts`):

- **Android** `versionName` = the full version (`1.2.3-beta.1` allowed); `versionCode` = a monotonic
  integer `major*1_000_000 + minor*1_000 + patch`.
- **Desktop** installer version = the numeric core only (`1.2.3`) — jpackage rejects `-beta`/`+build`
  suffixes in an MSI/DMG version, so the prerelease label lives only in the tag and Release name.
  Additionally, jpackage refuses a macOS app-version whose **first number is 0**, so a `0.x` tag's
  desktop major is bumped to `1` (e.g. `0.9.3` → desktop `1.9.3`); Android keeps the true `0.9.3`.
  Release from `1.x` to avoid the mismatch — the apps already sit at 1.0.

A local build with no `-PappVersion` keeps the historical `1.0` / `1`, so day-to-day work is
unchanged.

## Artifacts

Per app: macOS `.dmg` (arm64 **and** x64, signed + notarized), Windows `.exe` (Azure Trusted
Signing), Linux `.deb`, and a release `.apk`. All are attached to the Release and synced to
`s3://<bucket>/<owner>/<repo>/{release | pre-release/<version>}`.

## Signing is optional everywhere

Every signing path degrades to *unsigned* rather than failing the release, so the workflow runs to a
complete GitHub Release even with **no secrets at all** — useful on a fork or a first dry run:

| Platform | With secrets | Without |
| --- | --- | --- |
| macOS | signed (Developer ID) + notarized `.dmg` | unsigned `.dmg` (Gatekeeper will warn on launch) |
| Windows | Azure Trusted Signing on the `.exe` | unsigned `.exe` (SmartScreen will warn) |
| Android | signed release `.apk` | `…-release-unsigned.apk` |
| Linux `.deb` | — (deb is not code-signed) | — |

Availability is decided per platform: macOS/Android by whether `OP_SERVICE_ACCOUNT_TOKEN` is set,
Windows by whether `AZURE_CLIENT_ID` is set. A skipped signer emits a `::warning::` in the run log,
and the **macOS `.dmg` / Windows `.exe` get an `-unsigned` suffix in the filename** so an unsigned
artifact is obvious on the Release page (Android already names its unsigned APK `…-release-unsigned`).

## Secrets to configure

### GitHub repository secrets

| Secret | Purpose |
| --- | --- |
| `OP_SERVICE_ACCOUNT_TOKEN` | 1Password service-account token with read access to the **DevOps** vault. Used by every non-Windows job. |
| `AZURE_TENANT_ID` | Azure Trusted Signing (Windows). 1Password's action can't run on Windows runners, so Windows signing uses plain GitHub Secrets. |
| `AZURE_CLIENT_ID` | " |
| `AZURE_CLIENT_SECRET` | " |
| `AZURE_CODE_SIGNING_ACCOUNT_NAME` | " |
| `AZURE_CERTIFICATE_PROFILE_NAME` | " |

### 1Password items — reused from the old Orature pipeline (already exist)

Referenced by `op://` path in the workflow; no action needed if the DevOps vault still has them:

- `DevOps/Orature_CI_CD/Mac-Cert-and-Signing-Key` → `B64_CERT_AND_SIGNING` (Developer ID cert .p12, base64)
- `DevOps/Orature_CI_CD/MAC-P12-SIGNING-PASSWORD`
- `DevOps/Orature_CI_CD/App-Store-Connect-API-Key` → `app-store-connect-private-key.p8`
- `DevOps/Orature_CI_CD/MAC_NOTARY_ISSUER`, `…/MAC_NOTARY_KEY_ID`
- `DevOps/travis-nightlybuilds aws s3 …` → `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`, `S3_BUCKET`

### 1Password item — **new, must be created** for Android signing

Item `DevOps/BTT-Recorder_CI_CD/Android-Upload-Keystore` with fields:

- `keystore.b64` — the keystore, base64-encoded
- `store-password`, `key-alias`, `key-password`

Create the keystore once and upload it:

```bash
keytool -genkeypair -v -keystore release.keystore -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -i release.keystore | pbcopy   # paste into the keystore.b64 field
```

**Until this item exists, the Android job builds an _unsigned_ APK** (the `1password/load-secrets`
step is `continue-on-error`, and `app-*/build.gradle.kts` creates no release signingConfig without
the keystore). The release still publishes; the APK is just `…-release-unsigned.apk`. Keep the
keystore and its passwords safe — losing them means a new signing key, which Android treats as a
different app on upgrade.

## Partial builds still publish

A per-platform failure does not block the rest. `desktop` is a matrix job, so if (say) the macOS
cells fail, that job is marked failed — but `fail-fast: false` lets Windows/Linux finish, and
`sign-windows`/`publish` run under `if: !cancelled()`, attaching whatever built (Windows, `.deb`,
`.apk`) to the Release. The failed platform is simply missing from the Release, and its job is red in
the run. The Release step is skipped only if *no* platform produced an artifact.

## Not yet verified

The Gradle version injection and the unsigned-APK fallback are tested locally. The signing,
notarization, Azure, and S3 paths **cannot** be exercised without the real credentials and runners —
so treat the **first tagged run as the real test**, and watch:

- macOS: `Import Developer ID certificate` must find a `Developer ID Application` identity; notarize
  waits on `xcrun notarytool submit --wait`.
- Windows: `azure/trusted-signing-action` signs `winbin/*.exe`.
- The `publish` job needs the Release to not already exist for the tag.
