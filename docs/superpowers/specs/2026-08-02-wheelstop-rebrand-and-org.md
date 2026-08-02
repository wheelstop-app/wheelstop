# Wheelstop — independent rebrand, org, and release automation — design

**Date:** 2026-08-02
**Status:** draft for review

## Goal

Turn the fork into an independent, community-facing project — **Wheelstop** — under its own
GitHub **org**, with its own Android package (so it installs *alongside* the original), an
exclusivity preflight that keeps the two apps from fighting over the car's hardware, and bot-driven
release automation (release-please + Renovate), mirroring the `glinet4` / `comfort-hub` org pattern.
Attribution to upstream is preserved throughout; the signing key is preserved so the car keeps
auto-updating.

## Why

Upstream (`yash-srivastava/Overdrive-release`) ships a signing-key-gated blob (`libod.so`), exports
in bulk from a private tree with no release CI, and has weak provenance (see #204/#205 and the
provenance memory). The fork already has the independence infrastructure (open coefficients,
reproducible cert-gated signed builds, fork updater, sync + completeness tooling). This formalises
the split without confusing the community or claiming authorship of upstream's app.

## Decisions

1. **Name / domain:** app + project **Wheelstop**, domain `wheelstop.app`; display label "Wheelstop".
2. **Package:** `app.wheelstop.android` (reverse-DNS of `wheelstop.app` + `.android`) — a NEW
   `applicationId` = `namespace`, distinct from `com.overdrive.app`, so both apps coexist on the head
   unit.
3. **Signing:** keep the existing `df8fc138…` keystore. First install of the new package is fresh
   (adb), then the fork updater self-updates it in place (same cert). No car re-provision of the key.
4. **Org:** GitHub org **`wheelstop-app`**, standalone (NOT a GitHub fork). Upstream becomes a git
   remote only; the sync assistant already fetches it by URL.
5. **Automation identity:** a **GitHub App** ("wheelstop-release") whose token drives release-please
   and Renovate, so their PRs trigger CI (a plain `GITHUB_TOKEN` does not).
6. **Attribution:** keep upstream's MIT `LICENSE`; add a Wheelstop copyright line for the fork's
   additions; README credits upstream prominently as the origin.
7. **MQTT topic root:** telemetry publishes under `wheelstop/vehicle/telemetry` (rebranded from the
   current root). The Home Assistant side must resubscribe to the new root; this folds into the manual
   MQTT reconfiguration (Component C) — do it in the same sitting.

## Components

### A. Org + standalone repo migration
- Create org `wheelstop-app` with repos: `wheelstop` (the app), `.github` (org profile + reusable
  workflows + shared `renovate.json` / release-please config), `branding` (assets).
- Populate `wheelstop-app/wheelstop` by **pushing** the fork's `main` (after the independence PRs
  land) — not via the fork button — so it is a standalone repo with its own issues/PRs.
- Add `upstream = https://github.com/yash-srivastava/Overdrive-release.git` as a remote; the sync +
  completeness workflows (#11) retarget to run in the new repo unchanged (they already reference
  upstream by URL).
- Archive `shauneccles/Overdrive-release` with a README pointer to the new home; GitHub redirects
  keep old links working.

### B. Rebrand: applicationId + package-reference sweep
- `app/build.gradle.kts`: `applicationId = "app.wheelstop.android"`, **`namespace =
  "app.wheelstop.android"`** (full move — decided), `UPDATE_REPO = "wheelstop-app/wheelstop"`
  (both build types).
- **Full source-package move `com.overdrive.app` → `app.wheelstop.android`.** Because the namespace
  moves, the whole Java/Kotlin package tree moves with it: rename `src/main/java/com/overdrive/app/…`
  (and `src/test/…`) directory structure, update every `package`/`import` declaration, the R and
  BuildConfig references, and manifest component names. This is a large but mechanical refactor
  (IDE "Move package" or a scripted rename) — but it is NOT purely mechanical where **reflection,
  serialized state, or string class-names** are involved: R8 `-keep` rules, any `Class.forName` /
  string-referenced class, and any config that stores a fully-qualified class name must move too.
- Display name (`app_name` / launcher label / manifest `android:label`) → "Wheelstop".
- **MQTT topic root** → `wheelstop/vehicle/telemetry`: update the publisher's topic-root constant and
  the Home Assistant autodiscovery config/topics. Clear the old root's retained messages (or let them
  expire) so HA doesn't keep stale entities under both roots. FileProvider authority and any
  `wheelstop.app` URL refs move here too.
- **Native JNI bindings move too (safety-critical).** `app/src/main/cpp/surveillance/
  motion_pipeline_v2.cpp` uses **static JNI** — function names hardcoded as
  `Java_com_overdrive_app_surveillance_NativeMotion_*`. These are bound by *symbol name* to the class's
  package; rename the Java package without renaming these C++ functions and every native call throws
  `UnsatisfiedLinkError` at runtime (motion detection / surveillance dead), with a green build. The
  sweep MUST rename all `Java_com_overdrive_app_*` symbols to `Java_app_wheelstop_android_*` and
  rebuild the native lib. (tflite/tunnel prebuilts are unaffected — their JNI isn't in our package.)
- **Hardcoded `com.overdrive.app` sweep (highest-risk task).** Every literal package reference must be
  found and updated, including **runtime-GENERATED shell** (the daemon/watchdog scripts the app writes
  at runtime): the updater's `pm path com.overdrive.app` / `pm install`, the ADB self-connection
  target, daemon script paths (`/data/data/com.overdrive.app/…`, `$(pm path …)/lib/arm64/…`,
  `camera_daemon.lock` classpath), FileProvider authority, `am start` component names, notification
  channels, ProGuard/R8 rules, and any `sharedUserId`. A single missed daemon path silently breaks
  that daemon, so this gets an exhaustive grep + on-device verification task in the plan.
- Branding assets (launcher icon, splash) distinct enough to tell the two apps apart on the launcher.

### C. Exclusivity preflight — detect Overdrive, don't fight it

No config importer (see "Dropped" below). Instead, the one piece of migration logic worth building is
a guard that stops the two apps from destroying each other's runtime.

- On startup, **before** starting its own daemons, Wheelstop probes via the ADB self-connection
  (shell UID 2000): is `com.overdrive.app` installed (`pm path com.overdrive.app`)? Is it active — main
  process up (`pidof com.overdrive.app`) or a camera daemon it owns running?
- If Overdrive is present AND active, Wheelstop **refuses to start its daemons** and shows a
  blocking screen with the reason and one-tap actions (all doable from shell UID 2000):
  - **Stop now** — `am force-stop com.overdrive.app` + plant the shared disable sentinels / `pkill` its
    daemons.
  - **Disable** — `pm disable-user --user 0 com.overdrive.app` (survives reboot).
  - **Uninstall** — `pm uninstall com.overdrive.app` (guided, optional).
- Proceed only once **exclusive** (Overdrive absent, disabled, or stopped). If exclusivity can't be
  achieved (a contended resource still held), it stays blocked with the reason rather than silently
  fighting. Re-checks on resume so a later launch of the old app is caught.
- This makes the "run one at a time" constraint self-policing instead of a footnote.

**Dropped: the config importer.** ROI is poor — it only helps existing Overdrive users migrating, the
only tedious config (MQTT creds) is ENC-bound to the client id and must be re-entered anyway, and what
is cheaply importable (daemon toggles, vehicle model) is a couple minutes to redo. Migration is
therefore manual: install Wheelstop → let it retire Overdrive → re-enter config once. The runbook
lists what to copy (MQTT host/creds, enabled daemons, vehicle model). Revisit only on community demand.

### D. release-please (automated releases)
- Add `release-please` (via `googleapis/release-please-action`, pinned SHA) driven by Conventional
  Commits. It maintains `CHANGELOG.md`, bumps the version, and opens a **release PR**.
- Android versioning: release-please manages a version string; a build step maps it to
  `overdriveVersionCode` / `overdriveVersionName` (the same `-P` properties the build already reads),
  replacing #10's manual `workflow_dispatch(version)`.
- **Reconcile with #10's signed build:** merging the release PR creates a tag/GitHub release; the
  existing `release.yml` retriggers on that release event (instead of manual dispatch) → builds
  `:app:assembleRelease`, **cert-gates on `df8fc138`**, and uploads the signed APK + `SHA256SUMS` to
  the release the updater reads. The cert gate and same-cert guarantee are unchanged.
- Runs under the wheelstop-release App token so the release PR triggers CI.

### E. Renovate (dependency automation)
- `renovate.json` (shared from `wheelstop-app/.github`): update Gradle/AGP/Kotlin deps and **pinned
  GitHub Action SHAs** (Renovate updates digests while keeping them pinned — stays zizmor-clean).
- **Excluded / manual:** the digest-pinned native deps (opencv-mobile, OpenH264) and the vendored
  tunnel binaries — these are custom fetch+verify flows Renovate can't manage; documented as manual.
- Runs under the bot App token.

### F. Automation bot + org shared config
- GitHub App **"wheelstop-release"** (org-owned) — **already created**. Client ID
  `Iv23livK39Yzp2c9rPlq`; private key in the org action secret **`RELEASE_BOT_PRIVATE_KEY`**. Workflows
  mint a token via `actions/create-github-app-token` (`app-id: Iv23livK39Yzp2c9rPlq`, `private-key:
  ${{ secrets.RELEASE_BOT_PRIVATE_KEY }}`). Confirm its installed permissions are contents +
  pull-requests + workflows.
- `wheelstop-app/.github` holds: org `profile/README.md`, reusable `release-please` + `renovate`
  config, and (optionally) reusable build/zizmor workflow callables the app repo references.

## Continuity & security

- **Install-coexistence:** the new package installs alongside `com.overdrive.app`; the user can keep or
  remove the original. Nothing about the original is modified.
- **Update chain intact:** same `df8fc138` cert → after the first fresh install, the fork updater (#10)
  updates `app.wheelstop.android` in place. Cert gate in CI unchanged.
- **Attribution:** MIT `LICENSE` retained; README states Wheelstop is an independent fork of
  Overdrive by yash-srivastava, that the app is upstream's work, and what the fork adds and why.

### Runtime coexistence — install both, but run ONE at a time (v1 constraint)

Installing both is safe; **running both simultaneously is not**, and the spec must say so plainly.
The two apps contend for singleton, cross-UID resources:
- the **camera daemon** and the physical camera hardware,
- the **tunnel ports** (sing-box 8119 / Tailscale, zrok/cloudflared) and the ADB self-connection,
- the **MQTT client id** (a broker kicks the duplicate), and
- the **`/data/local/tmp` sentinels/locks** (`camera_daemon.lock`, `*.disabled`, park marker), which
  are **shared, un-namespaced paths** today.

So v1's supported model is: **both installed for migration/trial, only one "active" (daemon-owning) at
a time**, enforced by the **exclusivity preflight (Component C)** — Wheelstop refuses to start its
daemons while Overdrive is active, and offers to stop/disable/uninstall it. **True simultaneous
operation is explicitly OUT OF SCOPE for v1** — it would require namespacing every runtime resource
(per-package `/data/local/tmp/wheelstop/…`, a distinct MQTT client id, separate ports/camera
arbitration), a much larger project. Flag for a future spec if wanted.

### The old app freezes after migration

Once `shauneccles/Overdrive-release` is archived, the old `com.overdrive.app` on the car **stops
receiving auto-updates** (its updater points at the archived repo). That's fine if the user uninstalls
it post-migration; if they keep it, they must know it's frozen. The runbook should say: migrate →
verify Wheelstop → uninstall (or permanently disable the daemons of) the old app.

## Prerequisites (operator — you)

- Create the `wheelstop-app` GitHub org.
- Create/register the "wheelstop-release" GitHub App and install it on the org; store its App ID +
  private key as org secrets.
- Re-add the signing secrets (from the release-signing runbook) to the new repo.

## Scope boundaries (YAGNI)

- No change to the app's features, the signing key, or the install flow (`pm install -r`).
- **Both `applicationId` and `namespace` move to `app.wheelstop.android`** (decided), with the full
  source-package rename.
- **Simultaneous runtime operation of both apps is out of scope (v1 = one active at a time).** Full
  side-by-side operation would need namespaced runtime resources — a separate future spec.
- No HACS-style `registry` repo (single app; revisit if the org grows).
- **No config importer in v1** — migration is manual re-entry (see Component C). Revisit on demand.

## Open questions to resolve in the plan / on-device

1. Can shell UID 2000 (the ADB self-connection) `pm disable-user` / `pm uninstall` `com.overdrive.app`
   on this head unit, or only `am force-stop`? Determines which exclusivity actions the preflight can
   offer (force-stop is the guaranteed floor).
2. Full inventory of hardcoded references (grep sweep, incl. runtime-generated scripts + R8/reflection
   + native JNI) — completeness is safety-critical (a missed daemon path or JNI symbol silently breaks
   at runtime). The gate: **0 residual matches of BOTH `com.overdrive.app` (dotted) AND
   `com_overdrive_app` (JNI underscore form)** after the rename — except the preflight's intentional
   probe of the old package.
3. release-please `release-type` for an Android app (custom mapping to versionCode/Name).
