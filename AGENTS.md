# Working on this repo as an agent

Two Compose Multiplatform apps (Android + desktop) over one shared backend:
`app-orature` (Orature), `app-recorder` (BTT Recorder), `shared` (backend, audio engine,
persistence). About 97% of the code is `commonMain`.

## Build and test
- Desktop app: `./gradlew :app-orature:run`. Android debug APK: `./gradlew :app-orature:assembleDebug`.
- Unit tests: `./gradlew :shared:desktopTest`, `./gradlew :app-orature:desktopTest`.
- UI journeys (real app, headless, with screenshots in `app-orature/build/walkthroughs/`):
  `./gradlew :app-orature:desktopTest --tests '*e2e.Orature*'` (about 2 min).

## Verifying a change works
Do not stop at "it compiles" or "the screen looks right". Read
[docs/verification/README.md](docs/verification/README.md): start the app on a ready-made
state, do the feature's steps from its spec, and compare the state dump (what Orature actually
saved) before and after. Report the dumps with your verdict. If you add or change a feature,
add or update its spec there and its journey in `app-orature/src/desktopTest/.../e2e`.

## Test data
- `test-fixtures/`: small checked-in files (Genesis 1 source audio). Keep fixtures small.
- Never point tests or the verify app at your real Orature data folder.
