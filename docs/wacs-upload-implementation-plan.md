# WACS Sync (Upload / Clone / Fork / Pull) — Implementation Plan

**Status:** Plan for implementation. **Audience:** the coding agent (Sonnet) who will build this.
**Scope decided with product owner (2026-09):**

- **Two-way sync**: clone, fork, pull-a-chapter, commit, push. (Catalog-API pull of *source* comes later and is out of scope here.)
- **Transport**: **JGit** for git plumbing + a **hand-rolled git-LFS Batch API client over the existing OkHttp**. No shelling out to `git`/`git-lfs` binaries, no `jgit.lfs`.
- **Auth**: **username/password (HTTP Basic)**, held **in memory for the session only — never persisted** (no keystore, no DataStore, no disk). Re-prompt as needed. Behind a thin abstraction so **OAuth2 (PKCE) + persisted credentials drop in later**.
- **Targets**: **this KMP monorepo only** — shared client in `:shared`, UI wired into `:app-recorder` and `:app-orature` (Android + JVM desktop). Legacy JavaFX Orature is out of scope.
- **Async style**: **Kotlin coroutines** (`suspend`/`Flow`). Bridge to the Rx-shaped existing backend (`IProjectExporter: Single<>`) with `kotlinx-coroutines-rx2` only at the seam.
- **Localize all user-facing strings** (Compose `stringResource`/`Res`) — no hardcoded English.
- **Errors cross the `:shared`→app boundary as domain types, not transport exceptions.** `:shared` keeps the HTTP stack (retrofit/okhttp) and JGit off the apps' compile classpath by design; the app/UI layer must never catch `retrofit2.HttpException`, `java.io.IOException`, or JGit exceptions. Classify them into a small domain vocabulary *inside `:shared`* and let the UI localize by that. Established pattern: `WacsAuthException(reason: INVALID_CREDENTIALS | SERVER_ERROR | UNREACHABLE | UNKNOWN)`, thrown by `BasicAuthenticator`, mapped to strings in `WacsLoginViewModel`. **M2+ must follow this** — classify git/LFS/publish failures (auth, network, conflict, quota, integrity) into a `WacsPublishException`/reason (or a `Result` sealed type) in `:shared`; never surface a JGit or HTTP exception to the app.

> Companion reference: the client integration guide (§ numbers below refer to it) and the pipeline design doc. Local prototype: `http://localhost:3000`, org `AudioTranslation`, repo `audio-en-ulb-mrk`, default branch `master`.

---

## 0. The one rule that drives the whole design

**No external binaries required (verified in Spike A).** JGit is a pure-Java git implementation — **the user needs neither `git` nor `git-lfs` installed.** Clone/fetch/commit/push run entirely in the JVM; LFS transfer is our own OkHttp batch client. Verified by running the spike with `git`/`git-lfs` unreachable (empty `PATH`): clone + commit succeeded and the pointer was preserved. JGit invokes an external `git-lfs` binary **only** when a `filter.lfs.*` *command* is defined in git config (as on a developer machine) — the `SystemReader` isolation below removes that path, so behavior is identical and binary-free on a bare end-user machine and a dev machine alike.

Because we hand-roll LFS (no smudge/clean filter installed in JGit), **JGit sees LFS-tracked files as ordinary ~128-byte pointer text files.** Two consequences:

1. **On pull/clone**, JGit checks out the *pointer text*, not audio. We materialize real audio only for chapters the user opens, via our own Batch client. Nothing to fight in JGit.
2. **On commit, we must `git add` the POINTER text — never the audio bytes.** If the real `.flac`/`.wav` is present at the tracked path when we `add`, JGit commits the whole binary into git history. This is the #1 correctness pitfall. The publish path must write the pointer file to the tracked path *before* staging, and upload the real bytes out-of-band via the Batch API.

**Must-do to make (1) reliable — the sharpest Spike A finding.** JGit honors `filter.lfs.*` from the user's **global gitconfig**. On a Desktop where `git-lfs` is installed (very common — the dev prototype host had `filter.lfs.process = git-lfs filter-process` + git-lfs 3.6.1), **JGit silently shells out to the system git-lfs binary on checkout**, smudging pointers into real bytes and defeating our hand-rolled design — while Android (no binary, no global config) leaves pointers. That Desktop/Android split must be closed. **Fix (verified in the spike):** isolate JGit from host git config by installing a `SystemReader` whose `openUserConfig`/`openSystemConfig`/`openJGitConfig` return empty configs, so no inherited `filter.lfs.*`/`core.*`/`user.*` ever applies. Result: JGit checks out the ~128-byte pointer on both platforms, and our OkHttp batch client owns every transfer. (Setting `filter.lfs.*` to empty strings does **not** work — JGit then runs an empty filter and writes 0-byte files.) Apply the isolated `SystemReader` process-wide, once, before any JGit repo access.

> **Ready implementation:** `GitConfigIsolation.install()` — an idempotent, thread-safe object that installs exactly this isolated `SystemReader` (delegates everything to the platform default except the three config openers). Verified in the spike: with it installed, JGit reads `user.name` / `filter.lfs.process` / `core.autocrlf` from the host global config as **null** (default reader reads the real values). Call `install()` at app startup, in the git package's JVM source set. **Placement note:** it imports JGit, so it can only be added to the source tree *after* the JGit dependency is on `:shared` — dropping it in before that breaks module compilation. Drop-in file lives in the spike scratchpad.

Correct publish ordering (mirror git-lfs semantics):

```
1. Produce master audio (WAV now / FLAC later) + timing/alignment JSON, in a staging dir OUTSIDE the work tree.
2. For each LFS file: sha256 the bytes -> oid; build the pointer text (version/oid/size).
3. Write the pointer text to <worktree>/ingredients/<BOOK>/<ch>.<ext>.
4. Write/refresh .gitattributes, metadata.json, and the timing/alignment JSON (non-LFS, real content)
   — reuse what BurritoWrapperExporter already emits (Scripture Burrito alignment format), at its
   Burrito-defined path. Do NOT invent a `.timing` file (the client guide's `.timing` is wrong).
5. git add + commit (pointers + text only).
6. LFS Batch UPLOAD the real bytes (operation:"upload"), then POST verify if the action is present.
7. git push HEAD:master  (or push to fork; see fork flow).
```

Upload LFS objects **before** the ref push (git-lfs uploads in a pre-push hook), so the server never has a ref pointing at objects it doesn't hold.

---

## 1. Where the code goes

New package in `:shared` (cross-app, both platforms):

```
shared/src/commonMain/kotlin/org/bibletranslationtools/otter/common/domain/wacs/
├─ WacsConfig.kt              # base URL, org, default branch, timeouts (configurable)
├─ auth/
│  ├─ WacsCredential.kt       # Basic(user, secret) now; sealed so OAuth(token) slots in later
│  ├─ WacsSession.kt          # holds the credential IN MEMORY for the session; no persistence
│  └─ IWacsAuthenticator.kt   # login() -> WacsCredential; BasicAuthenticator now, OAuth later
├─ api/                       # Forgejo REST (Retrofit + kotlinx-serialization, existing deps)
│  ├─ ForgejoApi.kt           # whoami, getRepo, createRepo, forkRepo, createPullRequest, listRepos
│  └─ dto/…                   # request/response models
├─ git/                       # JGit plumbing (JVM APIs; commonMain via expect/actual only if needed)
│  ├─ IWacsGitClient.kt       # clone, fetch, commit, push, ffMerge, listLfsPointers
│  └─ JGitWacsGitClient.kt
├─ lfs/                       # hand-rolled LFS Batch API over OkHttp
│  ├─ LfsPointer.kt           # parse()/serialize()/isPointer()
│  ├─ LfsBatchClient.kt       # batch(download|upload), download(obj), upload(obj), verify
│  ├─ Sha256.kt               # streaming sha-256 (expect/actual or a common impl)
│  └─ LfsTransferProgress.kt
├─ layout/
│  ├─ WacsRepoLayout.kt       # ingredients/<BOOK>/<ch>.<ext> + alignment JSON + metadata.json paths
│  └─ MetadataJsonWriter.kt   # keep ingredients{} + currentScope in sync every commit
│                             # (timing/alignment JSON is produced by BurritoWrapperExporter — reuse it, no mapper)
└─ usecase/
   ├─ PublishChapterToWacs.kt # export -> layout -> commit -> LFS upload -> push to the user's fork
   ├─ ForkWacsRepo.kt         # REST fork of AudioTranslation/<repo> into the user's account -> clone fork
   ├─ CloneWacsRepo.kt        # clone (pointers only) + optional per-chapter lfs pull
   ├─ PullChapter.kt          # read pointer -> batch download -> verify -> materialize audio
   ├─ OpenPullRequest.kt      # PR from the user's fork back to AudioTranslation/<repo>
   └─ SyncWacsRepo.kt         # fetch + ff-merge (from upstream official repo)
```

Any JGit type that isn't visible from `commonMain` should be isolated behind the `IWacsGitClient`/`ILfs*` interfaces in `commonMain`, with the JGit-using implementation in `jvmMain`/shared-jvm source set. **Check first** whether `commonMain` in this project already resolves JVM types (it uses `java.io.File` in `commonMain` today — so plain JVM APIs are available in common here). If so, the interfaces and impls can both live in commonMain. Confirm during the spike.

**Reuse, don't reinvent, the artifact:** the repo already has `BurritoWrapperExporter` / `BackupProjectExporter` and the Scripture-Burrito writers under `.../resourcecontainer/burrito/`. The WACS layout is Burrito-shaped (`metadata.json` + `ingredients/`), **and the per-chapter timing is the Scripture Burrito alignment JSON that `BurritoWrapperExporter` already generates** — so the whole working-tree layout (audio + alignment JSON + metadata) falls out of the existing Burrito export. Prefer producing the working-tree layout by adapting that assembly rather than writing a new serializer. The empty `ExportType.PUBLISH` slot is the intended home — either implement a `PublishProjectExporter: IProjectExporter` that emits the working-tree layout, or have `PublishChapterToWacs` call the Burrito assembly directly. Decide during design; document the choice.

---

## 2. Dependencies to add

- **JGit** — `org.eclipse.jgit:org.eclipse.jgit` **6.10.0.202406032230-r** (Java 11 line). **Version resolved by Spike A (2026-09):** this exact version **dexes clean at `--min-api 24` with 0 errors/0 warnings** using the repo's existing `desugar_jdk_libs_nio` 2.1.5 against the compileSdk (android-37) jar, and clone+commit run on the JVM. The `_nio` desugar variant (already wired in `:shared` and `:app-recorder`) is what makes JGit's heavy `java.nio.file` use safe on Android 7 — do **not** switch to plain `desugar_jdk_libs`. Avoid 7.x (needs Java 17) and 5.13.x (older, no longer needed). Pin in `gradle/libs.versions.toml`. Transitive deps: `JavaEWAH`, `slf4j-api` (+ an slf4j binding).
- **kotlinx-coroutines-rx2** — only for the seam with the Rx exporter interface.
- Reuse existing **OkHttp 3.14.9 / Retrofit 2.9.0 / kotlinx-serialization** for REST + the LFS Batch client. (Note OkHttp 3.14.9 is old; the Batch client's range-GET/resumable-PUT must be written against the 3.x API. If it fights us, propose an OkHttp bump as a separate change — don't bundle it in.)
- **No credential-storage dependency for now.** The credential lives in memory only (`WacsSession`) — nothing persisted, so no `security-crypto`/keychain work in this phase. That surface arrives with OAuth2 later.

---

## 3. Component contracts (sketch — coroutines)

```kotlin
// auth — in-memory only this phase; nothing persisted
sealed interface WacsCredential {
    data class Basic(val username: String, val secret: String) : WacsCredential
    // data class OAuth(val accessToken: String) : WacsCredential  // later
}
class WacsSession {                      // process-lifetime holder; cleared on logout/app exit
    @Volatile var credential: WacsCredential? = null
}

// LFS
data class LfsPointer(val oid: String /* sha256 hex */, val size: Long)
interface LfsBatchClient {
    suspend fun downloadMissing(objs: List<LfsPointer>, into: (LfsPointer) -> Sink, onProgress: (Long, Long) -> Unit = {_,_->})
    suspend fun upload(objs: List<LfsObjectSource>, onProgress: (Long, Long) -> Unit = {_,_->})
}

// git
interface IWacsGitClient {
    suspend fun clone(remote: String, dir: File, cred: WacsCredential, branch: String = "master")
    suspend fun fetchAndFastForward(dir: File, cred: WacsCredential, branch: String = "master")
    suspend fun commit(dir: File, paths: List<String>, message: String)
    suspend fun push(dir: File, cred: WacsCredential, refspec: String = "HEAD:master")
    fun listLfsPointers(dir: File): List<Pair<String /*path*/, LfsPointer>>
}
```

Wire everything through **Koin** (existing DI, 3.5.6) and inject `WacsConfig` so base URL / org / target-remote are configurable, per the guide's §7 requirement.

---

## 4. REST surface (Forgejo, `/api/v1`)

Use Basic auth (user + secret) now; Bearer later. Minimum endpoints:

- `GET /user` — whoami / validate credential (login screen "Test connection").
- `GET /repos/{owner}/{repo}` — existence + default branch + clone URLs.
- `POST /repos/{owner}/{repo}/forks` — **the user's copy is a fork of `AudioTranslation/<repo>`** (this is the provisioning step; we do NOT create repos in the org).
- `POST /repos/{owner}/{repo}/pulls` — open a PR from the user's fork back to `AudioTranslation/<repo>` (the contribution).
- `GET /user/repos` (or `/repos/search`) — pick-a-repo UI for clone/pull.

**Repo model (confirmed with owner):** the **official** audio translation repo for a language lives in the **`AudioTranslation` org**; each user works in **their own fork**. Contribution is **fork → commit → push to fork → PR** back to the official repo. So publish targets the *fork*, and `SyncWacsRepo` fast-forwards from the official repo as upstream. (Edge case, not blocking v1: when no official repo exists yet for a language — first-ever recording — someone has to create the org repo first; treat as out of scope / admin task unless told otherwise.)

Send the existing WACS convention header **`X-Requested-With: WA-Tool-Orature`** (already used by `shared/build.gradle.kts`'s `downloadGLSources` to get past WACS bot-blocking / 403). LFS Batch requests reuse the **same credential** as git for the host, with `Accept`/`Content-Type: application/vnd.git-lfs+json` (guide §6).

Repo identity comes from project metadata: source/target language slugs + resource id + `ProjectMode` (`NARRATION`/`DIALECT`/`TRANSLATION`, set in `ProjectCreationViewModel.createWorkbook()`). Centralize the naming rule in `WacsRepoLayout` so client and server agree.

---

## 5. UI wiring (per app, localized)

- **Login screen** (new): host, username, password, "Test connection" → `GET /user`. Keep the result in `WacsSession` **in memory only — do not persist**; re-prompt when the session has no credential. Greenfield — no auth UI exists.
- **Recorder** (`:app-recorder`): add an **Upload/Publish to WACS** action next to the existing export flow — `ExportProjectViewModel.kt`, `ExportOptionsDialog.kt`, `ProjectManagementScreen.kt`, route in `ui/navigation/Navigation.kt`. Add **Clone / Fork from WACS** as a project-import entry point.
- **Orature** (`:app-orature`): equivalent hooks on its export/import surfaces.
- Progress: reuse `ExportProgressDialog.kt` patterns; feed it a `Flow<Progress>` covering commit → LFS upload (per-object bytes) → push, and download for pull. **Fetch/upload one chapter at a time** so a dropped link costs one file, not the whole repo (guide §8).

---

## 6. Risks & spikes (do these FIRST, before building features)

**Spike A — JGit on Android.** ⛔ **BLOCKER FOUND on API 24 (2026-09).** The desktop path is fully green (clone→commit→LFS upload→push→round-trip, verified by `WacsSyncDesktopTest`), and JGit 6.10 dexed clean at `--min-api 24`. **But JGit does not actually run on the Android 7 (API 24) runtime**, even after working around several nio gaps, because Android's *desugared* `java.nio.file` provider is incomplete:
  1. `FS_POSIX.setExecute` → `Files.getPosixFilePermissions` throws `UnsupportedOperationException` (repo init). Worked around with a custom `FS` (`WacsAndroidFS`) that does the exec-bit via `java.io`.
  2. `FS_POSIX.getAttributes` → `PosixFileAttributeView` is null → NPE (working-tree scan). Worked around via the same FS using `getFileAttributesBasic`.
  3. JGit 6.x/7.x call `InputStream.readNBytes` (Java 11, Android API 33) on subclass receivers that D8 cannot desugar → `NoSuchMethodError` at runtime. **Forced the pin to JGit 5.13.x (Java-8 line).** ⇒ dexing clean is necessary but NOT sufficient; only an on-device run catches this.
  4. **Unfixable via FS overrides:** JGit's object database can't read back objects it just wrote on API 24 — a purely local `init`+`commit` fails with `MissingObjectException` on an object `add()` just created. JGit's write/verify path (atomic create, rename, `FileSnapshot`) calls `java.nio.file` **directly** (not through `FS`), so a custom `FS` cannot cover it. This blocks clone/commit/push on API 24 entirely.

  **RESOLVED ✅ — gated to API 26+.** Confirmed on API 33 (Android 13): the full `WacsSyncInstrumentedTest` (clone → commit → LFS upload → push → fresh clone → `listLfsPointers` → download round-trip) **passes** on API 33 and **fails at commit on API 24** — native `java.nio.file` on API 26+ is the difference. WACS sync is therefore **gated to Android 8+ (API 26)** via `WacsPlatform.isGitSyncSupported` (expect/actual: `Build.VERSION.SDK_INT >= O` on Android, `true` on Desktop); `JGitWacsGitClient` calls `requireSupported()` on every op so an unsupported device gets a clear error, and the **UI must hide/disable sync when `isGitSyncSupported` is false**. The app's overall `minSdk` stays 24; only the sync feature requires 26+.

**Spike B — LFS Batch round-trip.** **Download half DONE (2026-09):** against the prototype, `POST .../info/lfs/objects/batch` returns a `download` action, GET of its `href` yields 9644 bytes, **sha256 == oid verified**. Confirmed **`SERVE_DIRECT=false`** (href back through Forgejo); client must still follow whatever `href`+`header` the response returns (be ready for `SERVE_DIRECT=true` presigned URLs) — **never hardcode** (guide §6, §8). **Upload direction DONE too (2026-09):** a full E2E probe against a fresh scratch repo (`user1/lfs-push-probe`) — create repo via API (201) → clone → commit `.gitattributes` + pointer → **LFS batch upload (PUT 200 + verify 200)** → **JGit push (OK)** → round-trip verify (server blob is a pointer; LFS re-download sha256 matches). Every WACS protocol step is now proven against the live prototype.

Other risks to design around:
- **Pointer-vs-bytes ordering** (§0) — the correctness lynchpin. Add a test asserting `git cat-file -p HEAD:<path>` (or JGit tree read) returns a pointer, not audio.
- **Credential handling** — in memory only this phase; **never logged, never written to `.git/config`/remote URL/query**. Use JGit `CredentialsProvider` + an OkHttp `Authorization` header built per-request; do not bake the secret into the clone URL. (Persistence + OAuth come later.)
- **Resume/backoff** — range GET for download, resumable/multipart PUT for upload; honor 429/5xx with backoff; idempotent re-push of unchanged chapters is a no-op.
- **`metadata.json` drift** — update `ingredients{}` + `currentScope` on *every* commit; the Catalog Indexer reads it (guide §3).
- **Two-way merge** — collaboration is whole-file per chapter, so conflicts are rare; `fetchAndFastForward` + "someone else changed this chapter" surfaced to the user is enough for v1. No line-merge.

---

## 7. Decisions locked / deferred

Resolved with the product owner (2026-09):
- **Timing** = the Scripture Burrito **alignment JSON already emitted by `BurritoWrapperExporter`** — not a bespoke `.timing` file (the client guide is wrong on this). Reuse the exporter.
- **Audio** = **ship WAV first**; FLAC is a later task.
- **Repo/contribution model** = official repo in the **`AudioTranslation` org**, user works in a **fork**, contributes via **fork + PR**. No org-side auto-create.
- **Auth** = **user/pass, in memory only, not persisted**. OAuth2 + persisted credentials deferred.

Deferred (revisit before those phases):
1. **OAuth2** — needs a registered WACS OAuth2 app (client id, redirect) + a decision on flow surface (system browser vs webview) per platform, plus the encrypted-storage surface dropped here.
2. **FLAC** — encoder choice (Android `MediaCodec` FLAC vs a JVM lib) when we move off WAV.
3. **First-ever language repo** — who/what creates the official `AudioTranslation/<repo>` when none exists yet (assumed an admin/out-of-scope step for now).

---

## 8. Suggested milestones

1. **M0 – Spikes / foundations.** **Landed in-repo (all compile on desktop + Android, pass `checkAndroidApiLevel` minSdk 24):**
   - JGit 6.10 dependency in `:shared` commonMain.
   - `wacs/git/GitConfigIsolation` — ignore host global/system git config (verified).
   - `wacs/lfs/LfsPointer` (+ `LfsPointerTest`, 6 tests green in `desktopTest`).
   - `wacs/auth/WacsCredential` — Basic now, sealed for OAuth later.
   - `wacs/lfs/LfsBatchClient` — hand-rolled Batch API (download+verify, upload+verify) over OkHttp/coroutines, built to the E2E-proven shape.
   - `wacs/git/IWacsGitClient` + `JGitWacsGitClient` — clone/fetch+ff/commit/push/listLfsPointers; installs `GitConfigIsolation` on construction.
   - `wacs/api/ForgejoApi` — Retrofit interface + DTOs: `whoAmI`, `getRepo`, `createRepo`, `forkRepo`, `createPullRequest` (auth per-call via `Authorization` header).
   - **Whole protocol proven E2E** against the prototype (create/commit/upload/verify/push/round-trip — §6).

   **✅ M0 complete.** DI wiring landed in M1 (and the converter turned out fine — `json.asConverterFactory(...)` from `com.jakewharton.retrofit2.converter.kotlinx.serialization` IS public; the earlier "Java-only" read was a wrong import package). Instrumented androidTest green on API 26+/33 (red on API 24 — that's the gate's justification). Scratch repo deleted. JGit pinned to **5.13.x** (not 6.10 — 6.x/7.x throw `NoSuchMethodError` at runtime on Android < 33; §2).
2. **✅ M1 – Auth (DONE, committed `40792aa`)**: `WacsCredential`/`WacsSession` (in-memory, never persisted) + login screen + `GET /user` "Test connection". `WacsAuthException` domain-error boundary established. Koin `wacsModule`.
3. **✅ M2 – Publish (DONE, in working tree)**: `WacsRepoLayout` + `MetadataJsonWriter` + `PublishChapterToWacs` (fork → clone → pointer+alignment+metadata → commit → LFS upload → push to the user's **fork**), reusing the Burrito export for audio + alignment JSON. `WacsPublishException` domain type. WAV masters. Per-chapter progress UI in Recorder. E2E integration test green against the prototype. **Note:** `ForkWacsRepo` + `CloneWacsRepo` from the old M3 are effectively done here (fork-or-get-existing + clone are inside the publish use case).
4. **✅ M4 – Contribute + Sync (DONE, in working tree)**: `IWacsGitClient.syncFromUpstream` (fast-forward the fork from the official repo before publishing, avoiding stale-fork non-fast-forward) + `PublishChapterToWacs.openContributionPullRequest` (once per session, idempotent find-then-create via `ForgejoApi.listPullRequests`/`createPullRequest`) + Recorder UI showing the submitted PR link (PR-step failure is a soft warning). E2E test asserts PR create-then-reuse. Failures classified into `WacsPublishException`. Remaining follow-up: the actual fork-behind-upstream fast-forward path isn't automation-tested (needs a second writer account); `metadata.json` scope-sync hardening not separately revisited (M2's writer handles it).
5. **~~M3 – Pull-as-source~~ → corrected to M3.1 (see below).** The pull *infrastructure* is done + committed (`21dc4fd`): repo-picker/`ListWacsRepos`, `CloneWacsRepo` (pointers-only), `WacsScopeReader` (reads `metadata.json` `ingredients{}` generically), `PullChapter` (LFS-download + sha256 verify). **But the semantics were wrong:** the product owner confirmed (2026-09) a Recorder/Orature user pulls a WACS repo to **restore/continue their own work**, not to get reference source (reference audio will come from the public data API later, separately). So the materialize step must produce **takes**, not source audio.
6. **✅ M3.1 – Restore from WACS (DONE, in working tree):** each pulled chapter is materialized as a **take** in the matching local project (not source audio), via `RestoreChapterFromWacs` reusing the M3 pull infra + the existing `SaveAudioAsNewTake` path; chapter-narration shape with markers from the pulled alignment JSON. **Dedup by LFS `oid` via a `WacsTakeProvenance` sidecar (`<take>.wacs-oid`), NOT `take_entity.checksum`** — that column is the checking-status feature's MD5 and writing a sha256 there would corrupt it (verified). Re-restore of an already-present chapter is a no-op. Repo→project by the `audio-<lang>-<edition>-<book>` name; v1 restores into an existing matching project only. **Selected-take is a user choice** (`SelectionPolicy.SELECT_RESTORED` default & race-free / `KEEP_CURRENT` opt-in), surfaced in the Restore UI. `ImportPulledChapterAsSource`/`SourceAudioImporter` kept unwired for the future reference-audio feature. Integration test (live WACS + real SQLite) asserts the take + dedup no-op. Follow-up: `KEEP_CURRENT` branch not automation-tested.
7. **M4 – done** (see item 4 above).
8. **M5 – remaining, four separable features (prioritize as you go):**
   - **✅ (a) Orature wiring (DONE, in working tree):** wired the publish + restore flows into `:app-orature`. **Decision: mirrored (not shared) — Orature keeps its own screens/ViewModels/theme per `app-orature/build.gradle.kts`'s own header comment, and `:shared` deliberately excludes `compose.material3`/`compose.foundation`/lifecycle/navigation from its API (see `shared/build.gradle.kts`), so there was no shared-Compose-UI location to extract into.** Ported `WacsLoginViewModel`/`WacsPublishViewModel`/`WacsPullViewModel` → `OratureWacsLoginViewModel`/`OratureWacsPublishViewModel`/`OratureWacsPullViewModel` (publish VM resolves the workbook via the shared `OpenWorkbook` use case + a single `workbookDescriptorId`, matching Orature's own convention, instead of Recorder's source/target-collection-id pair) and `WacsCommonUi`/`WacsPublishScreen`/`WacsPullScreen` → `OratureWacsCommonUi`/`OratureWacsPublishScreen`/`OratureWacsPullScreen`, all reusing the existing `wacs_*` strings in `:shared`. Publish entry point: the per-book Export dialog's existing (previously unwired) `ExportType.PUBLISH` "Publish" card now hands off to the WACS flow instead of running `sourceExporter`, gated on `WacsPlatform.isGitSyncSupported` (disabled + explained, not hidden) — mirrors the recorder app's Export Options repurposing the same slot. Restore entry point: a new "Restore from WACS" button in the Projects pane next to Import (`OratureRestoreFromWacsButton`); the destination screen itself gates on `isGitSyncSupported`, same as the recorder app's overflow-menu item. New nav routes `OratureWacsPublishRoute`/`OratureWacsPullRoute`; Koin bindings in `OratureViewModelModule.kt`. No `:app-recorder` files were touched. Compile + `:app-orature:assembleDebug` + existing `:app-orature:desktopTest` all green.
   - **(b) FLAC masters:** convert WAV→FLAC before publish (prod masters are FLAC). **Approach decided by the product owner (2026-09) — see [`docs/flac-integration-guide.md`](flac-integration-guide.md):** vendor Project Nayuki's pure-JVM FLAC library as a `:flac-lib` Gradle module + copy the streaming `StreamingFlacEncoder`/`StreamingFlacDecoder` Kotlin into a new JVM-shared source set (`jvmSharedMain`, NOT `commonMain` — it's `java.*`-only). Deterministic + byte-identical across Android/desktop, constant-memory (no `largeHeap`). In `PublishChapterToWacs`, convert `take.file` WAV→FLAC into staging, then pointer/oid/upload the `.flac` (`WacsRepoLayout` already tracks `*.flac`). Note: a WAV and its FLAC have different LFS oids (encoder-specific bytes — constraint #5). Confirm `:shared:checkAndroidApiLevel` stays green (Java-17 bytecode dexed for minSdk 24). Keep the `[flac-test]` fixes (RiceEncoder assert, StrictMath).
   - **(c) OAuth2 + persisted credentials:** register a WACS OAuth2 app; add the encrypted-storage surface deferred since M1.
   - **✅ (d) create-project-from-WACS (DONE, in working tree):** the device-loss restore case (no local project). **Source-text resolution, derived without guessing, from three independent reads (not a parse of the ambiguous `audio-<lang>-<edition>-<book>` name alone — several real gateway-language slugs contain hyphens, e.g. `es-419`, `pt-br`):** book from `metadata.json`'s `ingredients{}` scope (`WacsScopeReader.read`, already existed); language from its top-level `languages[].tag` (new `WacsScopeReader.readLanguageTags` — a Scripture-Burrito-required field untouched by the `type`/`type_specific` schema split); edition from the repo name with language+book now known and stripped (new `WacsRepoLayout.editionFromRepoName`). **v1 boundary: NARRATION only** — a WACS audio repo records only the spoken language, never a separate source language, so DIALECT can't be derived this way and isn't attempted (flagged as a product decision: prompt-the-user-for-a-source vs. stay out of scope). Source RC must be already-imported or bundled-and-sideloadable (`ILanguageRepository.getAvailableGatewaySourcesSuspend` + `ImportProjectUseCase.sideloadSource`) — no network fetch of an arbitrary source, no substitution; anything else fails cleanly with the new `WacsPullException.Reason.SOURCE_UNAVAILABLE`. New `CreateProjectFromWacs` use case (`:shared`) parses identity → resolves source RC → creates the project via the existing `CreateProject`/`ICollectionRepository.deriveProject` machinery (not reinvented) → restores every chapter in scope via the existing `RestoreChapterFromWacs` (not duplicated). Wired into both `WacsPullViewModel`/`WacsPullScreen` (Recorder) and `OratureWacsPullViewModel`/`OratureWacsPullScreen` (Orature, mirrored at low cost) as a "Create project and restore" action next to the existing "no matching project" message. **Bug found and fixed along the way:** `ImportProjectUseCase.sideloadSource` read the bundled GL zip via a plain JVM classpath lookup (`javaClass.classLoader.getResource`), but Compose Multiplatform resources are NOT on the JVM classpath (see `ComposeBundledContentSource`'s KDoc) — it threw `NullPointerException` the moment anything actually called it, in production as much as in a test; only ever exercised by a mocked unit test that never touched a real bundle. Fixed by routing through the `IBundledContentSource`/`GlSourceCatalog` ports the class already took as constructor parameters but never used. Opt-in `desktopIntegrationTest` (`CreateProjectFromWacsIntegrationTest`) starts from NO local project, runs against the live prototype (`AudioTranslation/audio-en-ulb-mrk`), and asserts a project is created (English ULB sideloaded) and chapter 1 lands as a take with provenance/sha256 == the LFS oid — green.

---

## 9. Definition of done for M2 (the first shippable slice)

- From Recorder, a user (logged in with in-memory user/pass) publishes a narrated chapter to **their fork** of an `AudioTranslation` repo; on the server the chapter path holds an **LFS pointer**, the object is in storage, the **alignment JSON** and `metadata.json` (with the ingredient listed) are committed, and re-pull by another client yields byte-identical audio (sha256 matches). All new strings localized. No secret persisted, logged, or in remote URLs.
