# Android e2e (`:app-recorder`)

Instrumented UI tests on a real device/emulator: mock audio + Koin via `RecorderTestApplication`, UiAutomator + `ActivityScenarioRule` (not ComposeTestRule). Desktop suite is separate (`desktopTest`).

## Run

```bat
gradlew.bat :app-recorder:connectedDebugAndroidTest -PminimalGlSources=true
```

One class (FQCN under `…e2e.flow`):

```bat
gradlew.bat :app-recorder:connectedDebugAndroidTest -PminimalGlSources=true -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.ClassName>
```

Desktop suite:

```bat
gradlew.bat :app-recorder:desktopTest -PminimalGlSources=true
```

Logs: tag `RecorderE2E`. Flow suites live in `e2e/flow/` (independent; no fixed order). JDK 17/21 fine.

## Critical rule

**Use `ActivityScenarioRule` + UiAutomator only.** `ComposeTestRule` owns the frame clock — taps show ripple but navigation never applies.

MainMenu Record / project open navigate on the click path (no `scope.launch { navState.first() }`). Reintroducing a deferred prefs await can leave home stuck under instrumented idling.

## Layout

```
e2e/          Runner, TestApplication, AndroidUiTestHelpers, E2eLog
e2e/harness/  mockAudioModule, seedGenesisProject()
e2e/flow/     Scenario / flow test classes
```

Runner: `RecorderE2ERunner` → `RecorderTestApplication` (Koin + mock audio).

## Config

| Property | Effect |
|----------|--------|
| `leaveApksInstalledAfterRun=false` | Uninstall app after tests (root `gradle.properties`; avoids dirty CI AVDs) |
| `-PminimalGlSources=true` | Download/list only `en_ulb` (pass on the Gradle command; not a repo default) |

Zips under `shared/.../files/content/` are gitignored; extras on disk still pack into the APK — delete them for a truly minimal bundle. Omit `-PminimalGlSources` for a full multi-language build.

## CI

GitHub Actions: [`.github/workflows/recorder-android-e2e.yml`](../../../.github/workflows/recorder-android-e2e.yml)  
Runs `:app-recorder:assembleDebug` + `assembleDebugAndroidTest` (with `-PminimalGlSources=true`) before the emulator, then `:app-recorder:connectedDebugAndroidTest` on an API 34 `google_apis` Pixel Tablet AVD (headless; Vulkan-off + boot settle). AVD cache is saved only after a clean snapshot create. CI uninstalls app/test packages before install. On timeout, screenshots go to tmpfiles.org (link in the log). Triggers on every push. On failure, uploads test reports.

## Open items

- Record/playback soft-passes if Stop never appears
- Device DB persists across runs
- Desktop suite CI (`.github/workflows/recorder-desktop-e2e.yml`) not added yet
