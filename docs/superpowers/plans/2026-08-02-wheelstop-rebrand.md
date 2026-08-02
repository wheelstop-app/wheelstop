# Wheelstop Rebrand + Org + Release Automation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the fork into the independent **Wheelstop** project — new package `app.wheelstop.android` (installs beside `com.overdrive.app`), an exclusivity preflight, MQTT rebrand, and bot-driven release automation — under `wheelstop-app/wheelstop`, keeping the `df8fc138` signing key.

**Architecture:** Three phases. **P1 — rebrand the code** (package/namespace move incl. native JNI, string sweep, MQTT root, display/branding, UPDATE_REPO). **P2 — automation** (release-please + Renovate under the `wheelstop-release` bot). **P3 — org migration** (push standalone, attribution, archive old). Verify each code task with the dockerized Gradle (`:app:testDebugUnitTest` + `:app:assembleDebug`); on-device items are flagged for the car.

**Tech Stack:** Android/Kotlin/Java, Gradle KTS, CMake/NDK (static JNI), GitHub Actions, release-please, Renovate, `gh`.

**Spec:** `docs/superpowers/specs/2026-08-02-wheelstop-rebrand-and-org.md`.

## Global Constraints

- Package **and** namespace → `app.wheelstop.android` (full source-package move). `applicationId == namespace`.
- **Completeness gate (every rebrand task ends on this):** `rg -n 'com\.overdrive\.app|com_overdrive_app' app/src` returns **0** matches — EXCEPT the single intentional old-package reference in the exclusivity preflight (Task 7). This includes runtime-generated shell, ProGuard, and native JNI.
- Keep the `df8fc138` keystore and the `pm install -r` install flow unchanged.
- `UPDATE_REPO = "wheelstop-app/wheelstop"`; `df8fc138` cert gate in CI unchanged.
- MQTT topic root → `wheelstop/vehicle/telemetry`.
- Bot = GitHub App **wheelstop-release**, client id `Iv23livK39Yzp2c9rPlq`, key in org secret `RELEASE_BOT_PRIVATE_KEY`.
- Build verification uses the dockerized Gradle (no host JDK): the `eclipse-temurin:17-jdk` + `and-sdk`/`and-gradle` + `~/.overdrive-build/android` invocation from the field manual. **Containers write as root** — `chown -R $(id -u):$(id -g) .` via a throwaway container after any build before touching git.

---

## Phase 1 — Rebrand the code

### Task 1: Move the source package `com.overdrive.app` → `app.wheelstop.android`

**Files:** every file under `app/src/{main,test,androidTest}/java/com/overdrive/app/**`; `app/build.gradle.kts`; `app/src/main/AndroidManifest.xml`.

**Interfaces:**
- Produces: the whole app under `app.wheelstop.android.*`; `applicationId`/`namespace` = `app.wheelstop.android`. `BuildConfig`/`R` regenerate under the new package.

- [ ] **Step 1: Move the directory trees**

```bash
for set in main test androidTest; do
  d=app/src/$set/java
  [ -d "$d/com/overdrive/app" ] || continue
  mkdir -p "$d/app/wheelstop"
  git mv "$d/com/overdrive/app" "$d/app/wheelstop/android"
  rmdir "$d/com/overdrive" "$d/com" 2>/dev/null || true
done
```

- [ ] **Step 2: Rewrite dotted package references in sources (NOT the native cpp — Task 2)**

```bash
grep -rlZ 'com\.overdrive\.app' app/src --include='*.kt' --include='*.java' --include='*.xml' \
  | xargs -0 sed -i 's/com\.overdrive\.app/app.wheelstop.android/g'
```

(Also catches string literals like the updater's `pm path` and the FileProvider authority — all of which should become the new package. The one deliberate old-package reference is added later in Task 7.)

- [ ] **Step 3: build.gradle.kts — applicationId + namespace**

Set both to `app.wheelstop.android`:

```kotlin
android {
    namespace = "app.wheelstop.android"
    defaultConfig { applicationId = "app.wheelstop.android" /* … */ }
}
```

- [ ] **Step 4: Compile-verify (expect the native link to still be broken until Task 2)**

Run the dockerized `:app:testDebugUnitTest`. Kotlin/Java must compile and unit tests pass. (Native/JNI is exercised at runtime, not by unit tests, so this passes even before Task 2.)
Then `chown -R $(id -u):$(id -g) .`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: move package com.overdrive.app -> app.wheelstop.android"
```

### Task 2: Rename the native JNI symbols and rebuild

**Files:** `app/src/main/cpp/surveillance/motion_pipeline_v2.cpp` (and any other `.cpp` with `Java_com_overdrive_app_*`).

- [ ] **Step 1: Find every static-JNI symbol**

```bash
rg -n 'Java_com_overdrive_app_' app/src/main/cpp
```

- [ ] **Step 2: Rename the underscore form**

```bash
grep -rlZ 'com_overdrive_app' app/src/main/cpp | xargs -0 sed -i 's/Java_com_overdrive_app_/Java_app_wheelstop_android_/g'
```

- [ ] **Step 3: Confirm the Java side matches**

The native class is `app.wheelstop.android.surveillance.NativeMotion` after Task 1; the JNI symbol `Java_app_wheelstop_android_surveillance_NativeMotion_*` must match its `native` method decls. Verify by grep that no `com_overdrive_app` remains in cpp.

- [ ] **Step 4: Build the native lib + APK**

Run the dockerized `:app:assembleDebug` (this compiles the NDK/`libsurveillance.so` with the new symbols and packages it). Expect BUILD SUCCESSFUL. `chown` back.
**On-device (flag for the car):** UnsatisfiedLinkError only manifests at runtime — surveillance/motion detection must be exercised on the head unit before trusting this.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor(jni): rename native symbols to Java_app_wheelstop_android_*"
```

### Task 3: Sweep the residual hardcoded references + enforce the gate

**Files:** wherever the sweep finds them — likely the updater (`pm path`/`pm install`), daemon/watchdog script generators, `FileProvider` authority in the manifest/code, notification channel ids, `app/proguard-rules.pro`.

- [ ] **Step 1: Run the gate**

```bash
rg -n 'com\.overdrive\.app|com_overdrive_app' app/src
```

- [ ] **Step 2: Fix each residual**

For each hit that is NOT the intentional preflight probe (which doesn't exist yet, so all hits are fixed now): update to `app.wheelstop.android`. Pay special attention to **runtime-generated shell** — daemon scripts that embed `/data/data/com.overdrive.app/…` or `pm path com.overdrive.app`. If a path is built from `BuildConfig.APPLICATION_ID` or a constant, Task 1 already fixed it; if it's a literal, fix it here.

- [ ] **Step 3: Re-run the gate — expect 0**

```bash
rg -n 'com\.overdrive\.app|com_overdrive_app' app/src   # must print nothing
```

- [ ] **Step 4: Build-verify + commit**

Dockerized `:app:testDebugUnitTest :app:assembleDebug`; `chown`; then:

```bash
git add -A && git commit -m "refactor: sweep residual com.overdrive.app references (0 remaining)"
```

### Task 4: MQTT topic root → `wheelstop/vehicle/telemetry`

**Files:** the MQTT publisher's topic-root constant; any Home Assistant autodiscovery config the app emits.

- [ ] **Step 1: Find the current topic root**

```bash
rg -n 'vehicle/telemetry|topicRoot|TOPIC_ROOT|discovery' app/src/main/java/app/wheelstop/android/mqtt
```

- [ ] **Step 2: Set the root to `wheelstop/vehicle/telemetry`**

Update the constant and any HA autodiscovery `topic`/`unique_id`/`object_id` prefixes derived from it.

- [ ] **Step 3: Note the retained-message cleanup**

Add a one-line code comment + a runbook note: the old root's retained discovery messages should be cleared (publish empty retained) or left to expire so HA doesn't keep stale entities under both roots. (Actual HA reconfig is manual, in the migration runbook.)

- [ ] **Step 4: Build-verify + commit**

```bash
git add -A && git commit -m "feat(mqtt): rebrand telemetry topic root to wheelstop/vehicle/telemetry"
```

### Task 5: Display name + branding

**Files:** `app/src/main/res/values*/strings.xml` (`app_name`), launcher icon/splash resources, `AndroidManifest.xml` `android:label`.

- [ ] **Step 1: Rename the display label** → "Wheelstop" (`app_name` in every `values*/strings.xml`).
- [ ] **Step 2: Distinct launcher icon** so the two apps are visually distinguishable on the head-unit launcher (a placeholder recolor is fine if final art isn't ready; note it in `branding`).
- [ ] **Step 3: Build-verify + commit**

```bash
git add -A && git commit -m "feat(brand): display name Wheelstop + distinct launcher icon"
```

### Task 6: Point the updater + workflows at the new repo

**Files:** `app/build.gradle.kts` (`UPDATE_REPO`); `.github/workflows/*` self-references to `shauneccles/Overdrive-release`.

- [ ] **Step 1: `UPDATE_REPO = "wheelstop-app/wheelstop"`** (both build types).
- [ ] **Step 2: Grep workflows for the old repo slug**

```bash
rg -n 'shauneccles/Overdrive-release' .github/workflows
```

Update self-references to `wheelstop-app/wheelstop` (leave the `yash-srivastava/Overdrive-release` upstream references in the sync/completeness workflows).

- [ ] **Step 3: Build-verify + commit**

```bash
git add -A && git commit -m "chore: point updater + workflows at wheelstop-app/wheelstop"
```

### Task 7: Exclusivity preflight — detect Overdrive, refuse to co-run

**Files:** Create `app/src/main/java/app/wheelstop/android/preflight/ExclusivityPreflight.kt` + a blocking UI; wire it into the startup path before daemons start; test `app/src/test/java/app/wheelstop/android/preflight/ExclusivityPreflightTest.kt`.

**Interfaces:**
- Consumes: the ADB self-connection (`AdbShellExecutor`/`AdbDaemonLauncher`), the daemon startup entry point.
- Produces: a gate that returns `EXCLUSIVE` / `CONTENDED(reason, actions)`.

- [ ] **Step 1: Write the failing test** (pure decision over injected shell outputs)

```kotlin
// classify(installed, active) -> EXCLUSIVE | CONTENDED
@Test fun notInstalled_isExclusive() { assertEquals(Verdict.EXCLUSIVE, ExclusivityPreflight.classify(installed=false, active=false)) }
@Test fun installedButDormant_isExclusive() { assertEquals(Verdict.EXCLUSIVE, ExclusivityPreflight.classify(installed=true, active=false)) }
@Test fun installedAndActive_isContended() { assertEquals(Verdict.CONTENDED, ExclusivityPreflight.classify(installed=true, active=true)) }
```

- [ ] **Step 2: Implement the pure classifier + the shell probes**

`OLD_PKG = "com.overdrive.app"` (the ONE intentional old-package reference — exempt from the gate). Probe via the self-connection: installed = `pm path $OLD_PKG` exit 0; active = `pidof $OLD_PKG` non-empty OR an owned camera daemon running. `classify(installed, active)` is pure and unit-tested.

- [ ] **Step 3: Blocking UI + actions**

If `CONTENDED`, show a blocking screen (reason + buttons), each an ADB self-connection command: **Stop** `am force-stop com.overdrive.app` (+ plant disable sentinels / `pkill` its daemons); **Disable** `pm disable-user --user 0 com.overdrive.app`; **Uninstall** `pm uninstall com.overdrive.app`. Re-probe after each; only start Wheelstop's daemons once `EXCLUSIVE`. Re-check on resume.

- [ ] **Step 4: Test + build-verify + commit**

Dockerized `:app:testDebugUnitTest`; `chown`; then:

```bash
git add -A && git commit -m "feat(preflight): refuse to co-run with Overdrive; offer stop/disable/uninstall"
```

---

## Phase 2 — Release automation

### Task 8: release-please + reconcile with the signed build

**Files:** `.github/workflows/release-please.yml` (new); `release-please-config.json` + `.release-please-manifest.json`; edit `.github/workflows/release.yml` trigger.

- [ ] **Step 1: release-please workflow** (pinned SHA), runs on push to `main`, mints a bot token:

```yaml
      - uses: actions/create-github-app-token@<pinned-sha>
        id: bot
        with:
          app-id: Iv23livK39Yzp2c9rPlq
          private-key: ${{ secrets.RELEASE_BOT_PRIVATE_KEY }}
      - uses: googleapis/release-please-action@<pinned-sha>
        with:
          token: ${{ steps.bot.outputs.token }}
          config-file: release-please-config.json
          manifest-file: .release-please-manifest.json
```

- [ ] **Step 2: Map the release version → the build's `-P` properties**

release-please owns the semver; a step derives `overdriveVersionName` (the semver) and a monotonic `overdriveVersionCode` (e.g. from the manifest version) and passes them to `assembleRelease`. (Optionally rename these gradle props to `wheelstopVersion*`; if so, update `build.gradle.kts`.)

- [ ] **Step 3: Retrigger the signed build on the release**

Change `release.yml` to also trigger `on: release: types: [published]` (keep `workflow_dispatch` as a manual fallback). Its cert gate (`df8fc138`) and `SHA256SUMS` upload are unchanged; the version comes from the release tag instead of manual input.

- [ ] **Step 4: Validate** — `python3 -c "import yaml,glob; [yaml.safe_load(open(f)) for f in glob.glob('.github/workflows/*.yml')]; print('ok')"` and `uvx zizmor --persona pedantic .github/workflows/release-please.yml .github/workflows/release.yml`. Commit.

### Task 9: Renovate

**Files:** `renovate.json` (or in `wheelstop-app/.github` as org-shared).

- [ ] **Step 1:** config to update Gradle/AGP/Kotlin deps and **pinned GitHub Action SHAs** (`helpers:pinGitHubActionDigests`), excluding the digest-pinned native deps + tunnel binaries (documented as manual). Runs under the bot token.
- [ ] **Step 2:** JSON-validate; commit.

---

## Phase 3 — Org migration (operator + docs)

### Task 10: Stand up `wheelstop-app/wheelstop` and cut over

**Operator + docs; not SDD-automatable end-to-end.**

- [ ] **Step 1: LICENSE + README + attribution.** Keep upstream's MIT `LICENSE`; add a Wheelstop copyright line for the fork's additions; write `README.md` stating Wheelstop is an independent fork of Overdrive by yash-srivastava, that the app is upstream's work, and what Wheelstop adds and why. Commit.
- [ ] **Step 2: Create the standalone repo** `wheelstop-app/wheelstop` (empty, NOT via the fork button). Push this branch's history: `git push git@github.com:wheelstop-app/wheelstop.git HEAD:main`.
- [ ] **Step 3: Remotes** — in the new repo add `upstream = https://github.com/yash-srivastava/Overdrive-release.git`; confirm the sync + completeness workflows run (they reference upstream by URL).
- [ ] **Step 4: Secrets** — add the signing secrets (release-signing runbook) to the new repo; confirm the `wheelstop-release` App is installed with contents + pull-requests + workflows.
- [ ] **Step 5: First release** — let release-please open its first release PR; merge; confirm `release.yml` builds a `df8fc138`-signed `app.wheelstop.android` APK + `SHA256SUMS`.
- [ ] **Step 6: On-car cutover** — adb-install the new APK fresh; the preflight retires the old app; re-enter config (MQTT under `wheelstop/vehicle/telemetry`, daemons, vehicle model); verify surveillance/blindspot/daemons/updater; then uninstall/disable `com.overdrive.app`.
- [ ] **Step 7: Archive** `shauneccles/Overdrive-release` with a README pointer to the new home.

---

## Self-Review

- **Spec coverage:** Component A → Task 10; B → Tasks 1–6; C → Task 7; D → Task 8; E → Task 9; F → Tasks 8/10. Covered.
- **The two hard parts have explicit gates:** JNI (Task 2 + runtime flag) and the 0-residual sweep (Task 3, enforced by the global gate).
- **Ordering:** Task 1 (dotted move) must precede Task 2 (JNI, which depends on the new Java class package) and Task 7 (which re-introduces the one intentional old-package literal AFTER the gate is green). release.yml edit (Task 8) sits on top of Task 6's `UPDATE_REPO`.
- **Placeholder scan:** the `<pinned-sha>` in Task 8 is resolved at implementation from the actions' latest release tags (pin like the repo's other workflows). Launcher art in Task 5 may be a placeholder — flagged.
