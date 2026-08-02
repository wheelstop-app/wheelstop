# Signed release pipeline + fork auto-update — design

**Date:** 2026-08-02
**Status:** approved (design)

## Goal

Give the fork a self-hosted release pipeline: CI builds a **signed release APK** and
publishes it to the fork's GitHub releases, and the app's auto-updater pulls from the
**fork** (`shauneccles/Overdrive-release`) instead of upstream. Because the signing key
matches what is already on the car, updates land in place with no data loss.

## Key facts that constrain the design

- The updater installs via `pm install -r -d <apk>` (through the app's own ADB
  self-connection). `pm install -r` **enforces the same signing certificate** — an APK
  signed with any other key is rejected by Android.
- The car currently runs a **debug** build signed with the keystore at
  `~/.overdrive-build/android/debug.keystore`: alias `androiddebugkey`, store/key password
  `android`, cert SHA-256 **`df8fc138481d279c019ff92137d104993de4a37d966504a7b7f78bb274c9a84e`**.
- The release `signingConfig` in `app/build.gradle.kts` is already env-driven
  (`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_PASSWORD` / `KEY_ALIAS`).
- The updater's source is a hardcoded constant `AppUpdater.GITHUB_REPO`
  (`yash-srivastava/Overdrive-release`); it hits `releases/tags/{channel}` with
  channel = `alpha` (a **rolling** release tag), picks the first `.apk` asset, and compares
  a version label (installed version tracked in `VERSION_FILE`
  = `/data/local/tmp/overdrive_version`).

## Decisions (from brainstorming)

1. **Sign with the existing `df8fc138` keystore** (loaded into CI as a secret), so
   auto-update works in place on the current car build — no uninstall, no prefs wipe.
2. **Publish signed release (R8/minified) builds**, but validate the release build on the
   car once before trusting auto-update (R8 has never been exercised on this fork).
3. **Update source is a `BuildConfig.UPDATE_REPO` field**, default
   `shauneccles/Overdrive-release`, switchable at build time.

## Components

### A. App — switch update source
- `app/build.gradle.kts`: add `buildConfigField("String", "UPDATE_REPO",
  "\"shauneccles/Overdrive-release\"")` to **both** build types (debug + release).
- `AppUpdater.java`: replace the `GITHUB_REPO` constant's use with `BuildConfig.UPDATE_REPO`
  at the single API-URL build site (keep the constant name or inline; one read-site).

### B. App — verify the download before installing
- After the APK is downloaded and before the `pm install` script runs, compute the
  downloaded file's SHA-256 and compare it to the expected digest fetched from the
  release's `SHA256SUMS` asset. On mismatch: abort the install, report a clear error, do
  not run `pm install`.
- This is defense-in-depth: `pm install -r`'s same-cert enforcement is the primary guard
  (a wrong-key APK is rejected by Android regardless); the digest check catches
  tamper/corruption **before** the install script kills the app process.
- Keep it tolerant: if the release has no `SHA256SUMS` asset (e.g. an older release), fall
  back to the existing behaviour (HTTPS download + same-cert install) rather than blocking
  updates — log that the digest was unverified.

### C. CI — signed release workflow (`.github/workflows/release.yml`)
- **Trigger:** `workflow_dispatch` with a `version` input (a monotonic string used in the
  asset name and release, e.g. `2026.08.02`). Deliberate click-to-release.
- **Build:** JDK 17 + Android SDK/NDK (mirror `build.yml`), then `:app:assembleRelease`
  with the signing env wired from secrets:
  - `KEYSTORE_FILE` → the decoded keystore path, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`,
    `KEY_ALIAS` from secrets.
- **Cert gate:** verify the built APK's signer SHA-256 == the pinned `df8fc138…` (same
  pattern as the tunnel-binary workflow). Fail the run on mismatch, so a wrong-key or
  mis-provisioned secret can never publish a release that would break auto-update.
- **Publish:** compute `SHA256SUMS` for the APK; upload the APK (versioned name, e.g.
  `overdrive-alpha-<version>.apk`) + `SHA256SUMS` to the rolling `alpha` release
  (`gh release create alpha … --clobber` or upload if it exists), with `contents: write`
  scoped to that job only. Set the release name/body to carry `<version>` so the updater's
  label comparison sees it as newer.
- zizmor-clean (pinned actions, `persist-credentials: false`, documented permissions),
  matching the repo's other workflows.

### D. Secrets + validation (operator steps — not code)
- **Secrets (user adds to the fork repo):**
  `SIGNING_KEYSTORE_B64` = `base64 -w0 ~/.overdrive-build/android/debug.keystore`,
  `SIGNING_KEYSTORE_PASSWORD` = `android`, `SIGNING_KEY_PASSWORD` = `android`,
  `SIGNING_KEY_ALIAS` = `androiddebugkey`. The plan will emit the exact `gh secret set`
  commands.
- **Release-build validation (before first real release):** build `:app:assembleRelease`
  locally, install it on the car in place (debug→release is same-cert `df8fc138`, so
  `pm install -r` succeeds and data is preserved), and verify the app + daemons +
  blind-spot render + MQTT all work. R8 stripping is the risk; this is the gate. Only after
  this passes should auto-update be relied on.

## Security model

- **Primary:** Android's `pm install -r` same-cert enforcement. A GitHub compromise that
  replaces the release APK without the `df8fc138` private key produces an APK the car
  **rejects**. The private key never leaves the operator's machine / GH secrets.
- **Secondary:** the pre-install SHA-256 verification (component B) catches
  corruption/tamper before the app kills itself for the install.
- **CI:** the cert gate (component C) prevents publishing a release the car couldn't
  install, turning a mis-signed build into a failed CI run rather than a bricked
  auto-update.

## Scope (YAGNI)

- Reuse the existing `alpha` channel and the existing `pm install -r` install flow —
  unchanged.
- No new release channels, no OTA delta/patching, no signature-pinning UI.
- No change to how the updater is triggered or how it kills/relaunches for install.

## Files expected to change

- `app/build.gradle.kts` — `UPDATE_REPO` BuildConfig field (both build types).
- `app/src/main/java/com/overdrive/app/updater/AppUpdater.java` — read `BuildConfig.UPDATE_REPO`;
  add the pre-install SHA-256 verification with graceful fallback.
- `.github/workflows/release.yml` — new signed-release workflow.

## Open items resolved during design

- Keystore creds verified: alias `androiddebugkey`, store/key password `android`, cert
  `df8fc138…` (matches the car) — so debug→release transition is same-cert and in-place.
