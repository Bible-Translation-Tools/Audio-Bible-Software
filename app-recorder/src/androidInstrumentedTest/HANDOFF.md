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
| `leaveApksInstalledAfterRun=true` | Keep app installed after tests (currently in root `gradle.properties`) |
| `-PminimalGlSources=true` | Download/list only `en_ulb` (pass on the Gradle command; not a repo default) |

Zips under `shared/.../files/content/` are gitignored; extras on disk still pack into the APK — delete them for a truly minimal bundle. Omit `-PminimalGlSources` for a full multi-language build.

## Open items

- Android instrumented e2e not in CI yet (desktop suite: `.github/workflows/recorder-desktop-e2e.yml`)
- Record/playback soft-passes if Stop never appears
- Device DB persists across runs
