# Signed Release Pipeline + Fork Auto-Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a signed release APK in CI, publish it to the fork's GitHub releases, and point the app's auto-updater at the fork — so updates land in place on the car via the existing same-cert `pm install -r` flow.

**Architecture:** Three code changes plus one workflow. (1) `BuildConfig.UPDATE_REPO` replaces the hardcoded upstream repo in `AppUpdater`. (2) A pre-install SHA-256 check verifies the downloaded APK against a published `SHA256SUMS` asset before `pm install` — defense-in-depth behind Android's same-cert enforcement. (3) A `workflow_dispatch` release workflow builds `:app:assembleRelease` with signing secrets, gates the output on the pinned `df8fc138…` certificate, and publishes the APK + `SHA256SUMS` to a rolling `alpha` release. A runbook documents the operator's one-time secret setup and the mandatory on-car R8 validation.

**Tech Stack:** Android/Kotlin/Java app, Gradle KTS, GitHub Actions, `gh` CLI, `apksigner`, JUnit + `org.json` (already on the unit-test classpath).

## Global Constraints

- **Signing certificate is pinned:** every published release APK's signer SHA-256 MUST equal `df8fc138481d279c019ff92137d104993de4a37d966504a7b7f78bb274c9a84e` (the cert already on the car). CI MUST fail the run if the built APK's signer differs — a mis-signed release must never be published.
- **Update repo default:** `shauneccles/Overdrive-release`. Channel: `alpha` (unchanged, rolling tag). Do not add channels.
- **Do not change the install command or flow.** `pm install -r -d <APK_PATH>` and its same-cert enforcement is the PRIMARY guard and stays exactly as-is. The SHA-256 check is added strictly *before* the existing install branch.
- **SHA-256 verification is defense-in-depth with graceful fallback:** a missing `SHA256SUMS` asset, an absent `sha256sum` tool, or an unreadable sums file → log "unverified" and PROCEED (same-cert install still protects). Only a definitive digest MISMATCH aborts the install.
- **All new file I/O in the updater goes through the existing `runShell(...)` idiom** (the ADB self-connection), never direct `java.io` reads of `/data/local/tmp` — the app UID cannot read that path; the daemon UID can. `runShell` is UID-safe and is what the existing size-check uses.
- **Workflows must be zizmor-clean:** pin every `uses:` to a full 40-char commit SHA (with a `# vX` comment), `persist-credentials: false` on checkout, top-level `permissions: contents: read` with `contents: write` scoped only to the publishing job, and never interpolate `${{ … }}` directly into a `run:` script — pass values through `env:`.

---

## File Structure

- `app/build.gradle.kts` — add `UPDATE_REPO` BuildConfig field to both build types (Task 1).
- `app/src/main/java/com/overdrive/app/updater/AppUpdater.java` — repo constant reads BuildConfig (Task 1); three digest helpers (Task 2); field + assignments + Step 2b integration (Task 3).
- `app/src/test/java/com/overdrive/app/updater/UpdateRepoConfigTest.java` — new, Task 1.
- `app/src/test/java/com/overdrive/app/updater/AppUpdaterDigestTest.java` — new, Task 2.
- `.github/workflows/release.yml` — new signed-release workflow, Task 4.
- `docs/release-signing-runbook.md` — new operator runbook, Task 5.

---

## Task 1: Point the updater at the fork via BuildConfig

**Files:**
- Modify: `app/build.gradle.kts:319` (release block) and `app/build.gradle.kts:327` (debug block)
- Modify: `app/src/main/java/com/overdrive/app/updater/AppUpdater.java:40`
- Test: `app/src/test/java/com/overdrive/app/updater/UpdateRepoConfigTest.java`

**Interfaces:**
- Produces: `BuildConfig.UPDATE_REPO` (String) = `"shauneccles/Overdrive-release"`; `AppUpdater.GITHUB_REPO` now equals it. All four existing `"https://api.github.com/repos/" + GITHUB_REPO` sites (lines 542, 2323, 2435, 2588) inherit the new value unchanged.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/overdrive/app/updater/UpdateRepoConfigTest.java`:

```java
package com.overdrive.app.updater;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.BuildConfig;
import org.junit.Test;

/** Guards the fork-update source: the app must check the fork, not upstream. */
public class UpdateRepoConfigTest {
    @Test
    public void updateRepoPointsAtFork() {
        assertEquals("shauneccles/Overdrive-release", BuildConfig.UPDATE_REPO);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*UpdateRepoConfigTest' --no-daemon --console=plain`
Expected: FAIL — `UPDATE_REPO` does not exist yet (compile error) or is absent.

- [ ] **Step 3: Add the BuildConfig field to both build types**

In `app/build.gradle.kts`, in the `release {` block, immediately after the `UPDATE_CHANNEL` line (currently line 319):

```kotlin
            // Auto-update source: this fork's releases, not upstream.
            buildConfigField("String", "UPDATE_REPO", "\"shauneccles/Overdrive-release\"")
```

Add the identical line in the `debug {` block, immediately after its `UPDATE_CHANNEL` line (currently line 327).

- [ ] **Step 4: Make the updater read the field**

In `AppUpdater.java`, replace line 40:

```java
    private static final String GITHUB_REPO = "yash-srivastava/Overdrive-release";
```

with:

```java
    private static final String GITHUB_REPO = BuildConfig.UPDATE_REPO;
```

(`BuildConfig` is already imported at line 9. Do not touch the four call sites — they read `GITHUB_REPO`.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*UpdateRepoConfigTest' --no-daemon --console=plain`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/overdrive/app/updater/AppUpdater.java app/src/test/java/com/overdrive/app/updater/UpdateRepoConfigTest.java
git commit -m "feat(updater): check the fork for releases via BuildConfig.UPDATE_REPO"
```

---

## Task 2: Pure digest helpers (parse + classify) with unit tests

Three package-private static helpers in `AppUpdater`, all unit-testable (`org.json:json` is on the test classpath, line 466 of `app/build.gradle.kts`).

**Files:**
- Modify: `app/src/main/java/com/overdrive/app/updater/AppUpdater.java` (add three static methods near `firstApkAsset`, ~line 494)
- Test: `app/src/test/java/com/overdrive/app/updater/AppUpdaterDigestTest.java`

**Interfaces:**
- Produces (all `static`, package-private):
  - `String sha256SumsAssetUrl(JSONArray assets)` → `browser_download_url` of the asset whose name equals `SHA256SUMS` (case-insensitive), else `""`.
  - `String expectedApkDigest(String sumsContent)` → lowercase hex digest from the first line of a `SHA256SUMS` file that references a `.apk`, else `""`.
  - `String classifyDigest(String expectHex, String actualHex)` → one of `"VERIFIED"`, `"MISMATCH"`, `"UNVERIFIED"`.
- Consumed by Task 3.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/overdrive/app/updater/AppUpdaterDigestTest.java`:

```java
package com.overdrive.app.updater;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AppUpdaterDigestTest {

    @Test public void findsSumsAssetCaseInsensitively() throws Exception {
        JSONArray assets = new JSONArray();
        JSONObject apk = new JSONObject();
        apk.put("name", "overdrive-alpha-v33.1.apk");
        apk.put("browser_download_url", "https://example/apk");
        JSONObject sums = new JSONObject();
        sums.put("name", "sha256sums"); // lower-case on purpose
        sums.put("browser_download_url", "https://example/SUMS");
        assets.put(apk);
        assets.put(sums);
        assertEquals("https://example/SUMS", AppUpdater.sha256SumsAssetUrl(assets));
    }

    @Test public void sumsAssetAbsentYieldsEmpty() throws Exception {
        JSONArray assets = new JSONArray();
        JSONObject apk = new JSONObject();
        apk.put("name", "overdrive-alpha-v33.1.apk");
        apk.put("browser_download_url", "https://example/apk");
        assets.put(apk);
        assertEquals("", AppUpdater.sha256SumsAssetUrl(assets));
    }

    @Test public void nullAssetsYieldEmpty() {
        assertEquals("", AppUpdater.sha256SumsAssetUrl(null));
    }

    @Test public void extractsApkDigestIgnoringOtherLines() {
        String sums =
            "0000000000000000000000000000000000000000000000000000000000000000  NOTES.txt\n"
          + "ABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789  overdrive-alpha-v33.1.apk\n";
        // lower-cased, apk line only
        assertEquals(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            AppUpdater.expectedApkDigest(sums));
    }

    @Test public void missingApkLineYieldsEmpty() {
        assertEquals("", AppUpdater.expectedApkDigest("deadbeef  NOTES.txt\n"));
    }

    @Test public void nullSumsContentYieldsEmpty() {
        assertEquals("", AppUpdater.expectedApkDigest(null));
    }

    @Test public void classifyMatch() {
        assertEquals("VERIFIED", AppUpdater.classifyDigest("aa", "AA")); // case-insensitive
    }

    @Test public void classifyMismatch() {
        assertEquals("MISMATCH", AppUpdater.classifyDigest("aa", "bb"));
    }

    @Test public void classifyMissingEitherIsUnverified() {
        assertEquals("UNVERIFIED", AppUpdater.classifyDigest("", "bb"));
        assertEquals("UNVERIFIED", AppUpdater.classifyDigest("aa", ""));
        assertEquals("UNVERIFIED", AppUpdater.classifyDigest(null, "bb"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*AppUpdaterDigestTest' --no-daemon --console=plain`
Expected: FAIL — the three helpers do not exist (compile error).

- [ ] **Step 3: Implement the three helpers**

In `AppUpdater.java`, add these static methods directly below `firstApkAsset(...)` (which ends around line 512). Match the existing `firstApkAsset` style (it also walks `JSONArray` with `optString`):

```java
    /**
     * URL of the release's {@code SHA256SUMS} asset (case-insensitive name
     * match), or {@code ""} if the release doesn't publish one. Mirrors
     * {@link #firstApkAsset(JSONArray)}'s null-tolerant walk.
     */
    static String sha256SumsAssetUrl(JSONArray assets) {
        if (assets == null) return "";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            if ("SHA256SUMS".equalsIgnoreCase(asset.optString("name", ""))) {
                return asset.optString("browser_download_url", "");
            }
        }
        return "";
    }

    /**
     * The expected APK digest from a {@code SHA256SUMS} file body. Returns the
     * lowercase hex from the first line that references a {@code .apk}, or
     * {@code ""} if none. Format per line: {@code <hex>  <filename>}.
     */
    static String expectedApkDigest(String sumsContent) {
        if (sumsContent == null) return "";
        for (String line : sumsContent.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.toLowerCase(java.util.Locale.ROOT).contains(".apk")) continue;
            int sp = trimmed.indexOf(' ');
            String hex = (sp < 0 ? trimmed : trimmed.substring(0, sp)).trim();
            return hex.toLowerCase(java.util.Locale.ROOT);
        }
        return "";
    }

    /**
     * VERIFIED when both digests are present and equal (case-insensitive),
     * MISMATCH when both present and differ, UNVERIFIED when either is missing
     * (the caller then falls back to same-cert-only install).
     */
    static String classifyDigest(String expectHex, String actualHex) {
        if (expectHex == null || expectHex.isEmpty()
                || actualHex == null || actualHex.isEmpty()) {
            return "UNVERIFIED";
        }
        return expectHex.equalsIgnoreCase(actualHex) ? "VERIFIED" : "MISMATCH";
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*AppUpdaterDigestTest' --no-daemon --console=plain`
Expected: PASS (all nine).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/overdrive/app/updater/AppUpdater.java app/src/test/java/com/overdrive/app/updater/AppUpdaterDigestTest.java
git commit -m "feat(updater): add SHA256SUMS parse + digest-classify helpers"
```

---

## Task 3: Verify the APK digest before install

Wire the helpers into the download→install flow: capture the sums URL at fetch time, and add a "Step 2b" verification between the existing size check and Step 3. This edits the concurrency-sensitive install method — preserve every existing comment and the ordering guarantees around it.

**Files:**
- Modify: `app/src/main/java/com/overdrive/app/updater/AppUpdater.java` — field near line 214; assignments at lines 573 and 2605; Step 2b block after line 903.

**Interfaces:**
- Consumes: `sha256SumsAssetUrl`, `expectedApkDigest`, `classifyDigest` (Task 2); existing `downloadApkOkHttp(url, path, callback)`, `buildDownloadCommand(url, path)`, `canWriteLocalTmp()`, `runShell(cmd, LaunchCallback)`, `postProgress`, `postInstallError`, `cleanupLeftoverApk()`, `APK_PATH`, `latestDownloadUrl`.

- [ ] **Step 1: Add the field**

In `AppUpdater.java`, directly below `private String latestDownloadUrl;` (line 214):

```java
    /** URL of the current release's SHA256SUMS asset, or "" if none published. */
    private String latestSha256SumsUrl = "";
```

- [ ] **Step 2: Capture the sums URL at both fetch sites**

At line 573, immediately after `latestDownloadUrl = apkUrl;`, add:

```java
                    latestSha256SumsUrl = sha256SumsAssetUrl(release.optJSONArray("assets"));
```

At line 2605, immediately after `latestDownloadUrl = apk[0];`, add:

```java
            latestSha256SumsUrl = sha256SumsAssetUrl(release.optJSONArray("assets"));
```

(In both scopes the `release` JSONObject is in scope — confirmed at lines 564 and 2600.)

- [ ] **Step 3: Add a constant for the sums temp path**

Next to `APK_PATH` (line 464), add:

```java
    private static final String SUMS_PATH = "/data/local/tmp/overdrive_update.sha256";
```

- [ ] **Step 4: Insert the Step 2b verification block**

In the install method, immediately **after** the size-check block closes (after line 903, the `}` ending `if (fileSize < 1_000_000) { … }`) and **before** the `// Step 3: Save update info` comment (line 905), insert:

```java
                // Step 2b: Verify the APK digest against the release's published
                // SHA256SUMS before we hand off to `pm install`. This is
                // defense-in-depth: `pm install -r` already rejects any APK not
                // signed with the on-device certificate, so the primary guard
                // holds regardless. A definitive MISMATCH aborts; a missing sums
                // asset / missing `sha256sum` tool / unreadable file falls back to
                // same-cert-only install (UNVERIFIED) rather than blocking updates.
                if (latestSha256SumsUrl == null || latestSha256SumsUrl.isEmpty()) {
                    Log.i(TAG, "No SHA256SUMS asset; same-cert install is the only guard.");
                } else {
                    postProgress(callback, "Verifying signature...");
                    String verdict = verifyApkDigest();
                    if ("MISMATCH".equals(verdict)) {
                        cleanupLeftoverApk();
                        postInstallError(callback, "Update rejected: APK digest does not match SHA256SUMS");
                        return;
                    }
                    Log.i(TAG, "APK digest verdict: " + verdict);
                }
```

- [ ] **Step 5: Add the `verifyApkDigest()` helper**

Add this private method next to `runDetachedInstall` (it uses the same download + runShell idioms). It downloads the sums file the same way the APK is downloaded (OkHttp for the daemon UID, shell tunnel otherwise), then reads both digests via `runShell` so it is UID-safe:

```java
    /**
     * Downloads the release SHA256SUMS and compares the on-disk APK's digest
     * against it. Returns "VERIFIED", "MISMATCH", or "UNVERIFIED". All file
     * access is via runShell so it works from both the daemon (UID 2000) and
     * app UID — the app UID cannot read /data/local/tmp directly. Any failure
     * degrades to "UNVERIFIED" (same-cert install still protects).
     */
    private String verifyApkDigest() {
        try {
            // 1. Fetch SHA256SUMS to SUMS_PATH, mirroring the APK download paths.
            if (canWriteLocalTmp()) {
                try {
                    downloadApkOkHttp(latestSha256SumsUrl, SUMS_PATH, null);
                } catch (Exception e) {
                    Log.w(TAG, "SHA256SUMS download failed: " + e.getMessage());
                    return "UNVERIFIED";
                }
            } else {
                final boolean[] done = {false};
                runShell(buildDownloadCommand(latestSha256SumsUrl, SUMS_PATH),
                        new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String m) {}
                    @Override public void onLaunched() { done[0] = true; synchronized (done) { done.notify(); } }
                    @Override public void onError(String e) { done[0] = true; synchronized (done) { done.notify(); } }
                });
                synchronized (done) { if (!done[0]) done.wait(30000); }
            }

            // 2. Read the sums file body and the APK's actual digest via shell.
            String sumsBody = runShellCapture("cat " + SUMS_PATH + " 2>/dev/null");
            String actual = runShellCapture("sha256sum " + APK_PATH + " 2>/dev/null | cut -d' ' -f1").trim();
            String expected = expectedApkDigest(sumsBody);

            // 3. Best-effort cleanup of the sums temp file.
            runShell("rm -f " + SUMS_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String e) {}
            });

            return classifyDigest(expected, actual);
        } catch (Exception e) {
            Log.w(TAG, "Digest verification error, proceeding same-cert-only: " + e.getMessage());
            return "UNVERIFIED";
        }
    }

    /** Runs a shell command via the ADB self-connection and returns its stdout
     *  (best-effort, "" on error). Blocks up to 15s. */
    private String runShellCapture(String cmd) {
        final StringBuilder out = new StringBuilder();
        final boolean[] done = {false};
        runShell(cmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) { if (m != null) out.append(m).append('\n'); }
            @Override public void onLaunched() { done[0] = true; synchronized (done) { done.notify(); } }
            @Override public void onError(String e) { done[0] = true; synchronized (done) { done.notify(); } }
        });
        try { synchronized (done) { if (!done[0]) done.wait(15000); } } catch (InterruptedException ignored) {}
        return out.toString();
    }
```

**Implementer note:** verify the exact signatures of `downloadApkOkHttp`, `buildDownloadCommand`, `canWriteLocalTmp`, and `runShell`'s callback type against the file before wiring (grep them). If `downloadApkOkHttp`'s callback parameter is non-null-required, pass a no-op `InstallCallback` instead of `null`. If a `runShellCapture`-equivalent already exists in the file, reuse it rather than adding a duplicate. Do not otherwise alter the install method's ordering or comments.

- [ ] **Step 6: Build and run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest --no-daemon --console=plain`
Expected: PASS (existing suite + Task 1/2 tests). This task's flow itself is not unit-tested — it depends on the ADB self-connection and real `/data/local/tmp` — and is validated on-car per Task 5.

- [ ] **Step 7: Assemble debug to confirm it compiles**

Run: `./gradlew :app:assembleDebug --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/overdrive/app/updater/AppUpdater.java
git commit -m "feat(updater): verify APK digest vs SHA256SUMS before install"
```

---

## Task 4: Signed-release CI workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes secrets: `SIGNING_KEYSTORE_B64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_PASSWORD`, `SIGNING_KEY_ALIAS` (set by the operator, Task 5).
- Produces: a rolling `alpha` GitHub release carrying `overdrive-alpha-<version>.apk` + `SHA256SUMS`, gated on the pinned cert.

- [ ] **Step 1: Create the workflow**

Create `.github/workflows/release.yml`. Mirror `build.yml`'s pinned action SHAs, JDK/NDK/CMake setup, and caches exactly (copy the SHAs verbatim from `build.yml` so they stay consistent):

```yaml
name: Release

# Manually-triggered signed release. Builds :app:assembleRelease with the
# df8fc138 signing key (loaded from secrets), refuses to publish anything signed
# with a different certificate, and pushes the APK + SHA256SUMS to the rolling
# "alpha" release the in-app updater reads. Same-cert signing is what lets the
# car install the update in place (pm install -r) with no data wipe.
on:
  workflow_dispatch:
    inputs:
      version:
        description: "Version label baked into the asset name, e.g. v33.1 (must sort newer than what's on the car)"
        required: true
        type: string

concurrency:
  group: release
  cancel-in-progress: false

permissions:
  contents: read

jobs:
  release:
    name: Build, cert-gate, publish
    runs-on: ubuntu-latest
    timeout-minutes: 60
    permissions:
      contents: write # create/update the alpha release
    steps:
      - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          persist-credentials: false

      - name: Set up JDK 17
        uses: actions/setup-java@d7793b545071e98d581d3bf084a51c3213318a07 # v4
        with:
          java-version: "17"
          distribution: temurin

      - name: Set up Android SDK
        uses: android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407 # v3

      - name: Install NDK + CMake
        run: sdkmanager --install "ndk;26.1.10909125" "cmake;3.22.1"

      - name: Cache Gradle
        uses: actions/cache@0057852bfaa89a56745cba8c7296529d2fc39830 # v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties', 'gradle/libs.versions.toml') }}
          restore-keys: gradle-${{ runner.os }}-

      - name: Cache native dependencies
        uses: actions/cache@0057852bfaa89a56745cba8c7296529d2fc39830 # v4
        with:
          path: |
            app/src/main/cpp/opencv
            app/src/main/cpp/openh264
          key: native-deps-${{ runner.os }}-${{ hashFiles('app/build.gradle.kts') }}

      - name: Decode signing keystore
        env:
          KEYSTORE_B64: ${{ secrets.SIGNING_KEYSTORE_B64 }}
        run: |
          set -euo pipefail
          if [ -z "${KEYSTORE_B64:-}" ]; then
            echo "::error::SIGNING_KEYSTORE_B64 secret is not set"; exit 1
          fi
          printf '%s' "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/release.jks"

      - name: Assemble signed release
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/release.jks
          KEYSTORE_PASSWORD: ${{ secrets.SIGNING_KEYSTORE_PASSWORD }}
          KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
          KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
        run: ./gradlew :app:assembleRelease --no-daemon --console=plain

      - name: Gate on the pinned signing certificate
        env:
          EXPECTED_CERT: df8fc138481d279c019ff92137d104993de4a37d966504a7b7f78bb274c9a84e
        run: |
          set -euo pipefail
          APK=$(find app/build/outputs/apk/release -name "*.apk" | head -1)
          echo "Signed APK: $APK"
          APKSIGNER=$(find "$ANDROID_SDK_ROOT/build-tools" -name apksigner | sort -V | tail -1)
          echo "Using $APKSIGNER"
          GOT=$("$APKSIGNER" verify --print-certs "$APK" \
            | grep -i "certificate SHA-256 digest" | head -1 \
            | tr 'A-F' 'a-f' | grep -oE '[0-9a-f]{64}')
          echo "signer SHA-256: $GOT"
          if [ "$GOT" != "$EXPECTED_CERT" ]; then
            echo "::error::signer cert $GOT != pinned $EXPECTED_CERT — refusing to publish"; exit 1
          fi

      - name: Stage assets (versioned APK + SHA256SUMS)
        env:
          VERSION: ${{ inputs.version }}
        run: |
          set -euo pipefail
          APK=$(find app/build/outputs/apk/release -name "*.apk" | head -1)
          mkdir -p dist
          cp "$APK" "dist/overdrive-alpha-${VERSION}.apk"
          ( cd dist && sha256sum "overdrive-alpha-${VERSION}.apk" | tee SHA256SUMS )

      - name: Publish to the rolling alpha release
        env:
          GH_TOKEN: ${{ github.token }}
          VERSION: ${{ inputs.version }}
        run: |
          set -euo pipefail
          NOTES="Signed release ${VERSION}. Cert-gated (df8fc138). Installs in place via same-cert pm install -r."
          if gh release view alpha >/dev/null 2>&1; then
            gh release upload alpha "dist/overdrive-alpha-${VERSION}.apk" dist/SHA256SUMS --clobber
            gh release edit alpha --title "alpha ${VERSION}" --notes "$NOTES" --target "$GITHUB_SHA"
          else
            gh release create alpha \
              "dist/overdrive-alpha-${VERSION}.apk" dist/SHA256SUMS \
              --title "alpha ${VERSION}" --notes "$NOTES" --target "$GITHUB_SHA"
          fi
```

- [ ] **Step 2: Sanity-check YAML locally**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('yaml ok')"`
Expected: `yaml ok`. (Full validation is the repo's own `zizmor.yml` + `actionlint` on the PR, plus the operator's `workflow_dispatch` dry-run in Task 5 — a live run needs the secrets, which CI in a PR does not have.)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci: signed release workflow, cert-gated, publishes to alpha"
```

---

## Task 5: Operator runbook (secrets + on-car R8 validation)

Documents the two things the operator must do by hand: load the signing secrets, and validate the R8/minified release build on the car before trusting auto-update. This is a docs task; no code.

**Files:**
- Create: `docs/release-signing-runbook.md`

- [ ] **Step 1: Write the runbook**

Create `docs/release-signing-runbook.md`:

```markdown
# Release signing — operator runbook

The CI (`.github/workflows/release.yml`) signs with the **same** keystore already
on the car (cert `df8fc138…`), so releases install in place with no data wipe. Two
steps are manual because they need the private key and a physical car.

## 1. One-time: load the signing secrets

From a machine with the keystore (`~/.overdrive-build/android/debug.keystore`) and
`gh` authenticated against `shauneccles/Overdrive-release`:

    gh secret set SIGNING_KEYSTORE_B64      --repo shauneccles/Overdrive-release \
      --body "$(base64 -w0 ~/.overdrive-build/android/debug.keystore)"
    gh secret set SIGNING_KEYSTORE_PASSWORD --repo shauneccles/Overdrive-release --body "android"
    gh secret set SIGNING_KEY_PASSWORD      --repo shauneccles/Overdrive-release --body "android"
    gh secret set SIGNING_KEY_ALIAS         --repo shauneccles/Overdrive-release --body "androiddebugkey"

The keystore's cert SHA-256 must be
`df8fc138481d279c019ff92137d104993de4a37d966504a7b7f78bb274c9a84e`
(check: `keytool -list -v -keystore ~/.overdrive-build/android/debug.keystore -storepass android`).
CI refuses to publish anything signed with a different cert.

## 2. Before the first real release: validate the R8 build on the car

The car has only ever run **debug** builds. The release build is R8/minified —
stripping could remove something reflected-into at runtime. Validate once:

1. Build locally with the signing env (same as CI):

       KEYSTORE_FILE=~/.overdrive-build/android/debug.keystore \
       KEYSTORE_PASSWORD=android KEY_PASSWORD=android KEY_ALIAS=androiddebugkey \
       ./gradlew :app:assembleRelease

2. Install in place over the running debug build (same cert → no wipe):

       adb install -r app/build/outputs/apk/release/app-release.apk

3. With the vehicle in READY, confirm: app launches; camera daemons start;
   blind-spot card renders; MQTT entities report to Home Assistant; the in-app
   updater screen loads and shows the fork as its source.

Only after this passes should you rely on auto-update. If R8 strips something,
add the needed `-keep` rules to the ProGuard config and repeat.

## 3. Cutting a release

`gh workflow run release.yml -f version=v<NN.N>` (or the Actions UI). The `version`
must sort **newer** than the label currently on the car, or the updater won't offer
it. The run builds, cert-gates, and publishes to the rolling `alpha` release; the
car picks it up on its next update check.
```

- [ ] **Step 2: Commit**

```bash
git add docs/release-signing-runbook.md
git commit -m "docs: release-signing operator runbook (secrets + on-car R8 validation)"
```

---

## Self-Review

- **Spec coverage:** Component A → Task 1; Component B → Tasks 2+3; Component C → Task 4; Component D → Task 5. All four spec components covered.
- **Placeholder scan:** no TBD/TODO; `<version>`/`<NN.N>` are operator inputs by design, documented as such.
- **Type consistency:** helper names `sha256SumsAssetUrl` / `expectedApkDigest` / `classifyDigest` are identical across Task 2 (definition + tests) and Task 3 (call sites). Field `latestSha256SumsUrl` and constant `SUMS_PATH` used consistently. `verdict` strings `VERIFIED`/`MISMATCH`/`UNVERIFIED` match between `classifyDigest` and the Step 2b branch.
- **Line-number caveat:** all line numbers are as-of plan authoring; each earlier task shifts later lines. Implementers must anchor on the quoted surrounding code (comments/signatures), not the raw line numbers.
```
