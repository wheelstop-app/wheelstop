# Blind-spot clarity + frame-rate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise the blind-spot reveal frame-rate to 25 fps and add an opt-in, tunable contrast + subtle sharpen to the stitched blind-spot card — without touching the coefficient math or the stitch geometry.

**Architecture:** Two config-driven levers, neutral by default so output is unchanged until dialed. fps is an existing config default (`blindspot.activeFps`) that already feeds `RecordingModeManager`'s reveal intent — just change the default. Clarity is two new config values plumbed config → `GpuSurveillancePipeline` → `GpuStreamScaler` → two fragment-shader uniforms, applied to the stitched colour inside `odBlend` before the card framing.

**Tech Stack:** Kotlin (config), Java (GL pipeline), GLSL ES 2.0 (shader), JS (web editor), JUnit (config-helper tests). Builds via the `overdrive-build` Docker image; on-device tests over adb (tailscale `100.68.86.26:5555`).

## Global Constraints

- **Neutral defaults are mandatory:** `blindspot.contrast` default `1.0`, `blindspot.sharpen` default `0.0`. At these values the shader output must be bit-identical to today (contrast is an identity map; sharpen block is guarded off). This preserves the emulator-verified `BsCoefficients` pipeline.
- **Do not modify** `com.overdrive.app.blindspot.BsCoefficients` or the 11 stitch geometry params. This is a safety surface.
- **Clamp ranges (safety caps):** contrast `0.5..2.0`, sharpen `0.0..1.0`, activeFps `1..30` (HAL-supported subset `{8,15,25}`; default `25`).
- **Clarity applies to the blind-spot card only** (inside `odBlend`, views 7/8) — never the recorder lane or other views.
- **Build:** `docker run --rm -v "$PWD":/work -v and-gradle:/root/.gradle -v and-sdk:/opt/android-sdk -v /home/shaunes/.overdrive-build/android:/root/.android -w /work overdrive-build:latest ./gradlew <tasks> --offline`
- **Signing:** the `-v /home/shaunes/.overdrive-build/android:/root/.android` mount is required or `adb install -r` is rejected (cert `df8fc138…`).

---

### Task 1: Config — fps default + clarity keys, with pure testable helpers

**Files:**
- Modify: `app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt` (near the existing `getBlindSpotActiveFps` at ~1545 and the blindspot defaults at ~658-664)
- Test: `app/src/test/java/com/overdrive/app/config/BlindSpotConfigTest.kt` (create)

**Interfaces:**
- Produces (all `@JvmStatic` on `UnifiedConfigManager`):
  - `blindSpotActiveFps(bs: JSONObject): Int` — pure; `optInt("activeFps", 25).coerceIn(1, 30)`
  - `blindSpotContrast(bs: JSONObject): Float` — pure; `optDouble("contrast", 1.0).toFloat().coerceIn(0.5f, 2.0f)`
  - `blindSpotSharpen(bs: JSONObject): Float` — pure; `optDouble("sharpen", 0.0).toFloat().coerceIn(0.0f, 1.0f)`
  - `getBlindSpotContrast(): Float` = `blindSpotContrast(getBlindSpot())`
  - `getBlindSpotSharpen(): Float` = `blindSpotSharpen(getBlindSpot())`
  - `getBlindSpotActiveFps(): Int` (existing) now delegates to `blindSpotActiveFps(getBlindSpot())`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/overdrive/app/config/BlindSpotConfigTest.kt`:

```kotlin
package com.overdrive.app.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BlindSpotConfigTest {
    @Test fun activeFpsDefaultsTo25() =
        assertEquals(25, UnifiedConfigManager.blindSpotActiveFps(JSONObject()))

    @Test fun activeFpsHonoursConfigAndClamps() {
        assertEquals(25, UnifiedConfigManager.blindSpotActiveFps(JSONObject().put("activeFps", 25)))
        assertEquals(30, UnifiedConfigManager.blindSpotActiveFps(JSONObject().put("activeFps", 99)))
        assertEquals(1,  UnifiedConfigManager.blindSpotActiveFps(JSONObject().put("activeFps", 0)))
    }

    @Test fun contrastDefaultsToNeutralOne() =
        assertEquals(1.0f, UnifiedConfigManager.blindSpotContrast(JSONObject()), 0f)

    @Test fun contrastClampsToSafetyRange() {
        assertEquals(2.0f, UnifiedConfigManager.blindSpotContrast(JSONObject().put("contrast", 9.0)), 0f)
        assertEquals(0.5f, UnifiedConfigManager.blindSpotContrast(JSONObject().put("contrast", 0.1)), 0f)
    }

    @Test fun sharpenDefaultsToOff() =
        assertEquals(0.0f, UnifiedConfigManager.blindSpotSharpen(JSONObject()), 0f)

    @Test fun sharpenClampsToSafetyRange() {
        assertEquals(1.0f, UnifiedConfigManager.blindSpotSharpen(JSONObject().put("sharpen", 5.0)), 0f)
        assertEquals(0.0f, UnifiedConfigManager.blindSpotSharpen(JSONObject().put("sharpen", -1.0)), 0f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `docker run --rm -v "$PWD":/work -v and-gradle:/root/.gradle -v and-sdk:/opt/android-sdk -v /home/shaunes/.overdrive-build/android:/root/.android -w /work overdrive-build:latest ./gradlew :app:testDebugUnitTest --offline --tests "com.overdrive.app.config.BlindSpotConfigTest"`
Expected: FAIL — unresolved reference `blindSpotActiveFps` / `blindSpotContrast` / `blindSpotSharpen`.

- [ ] **Step 3: Add the pure helpers and delegate the getters**

In `UnifiedConfigManager.kt`, add near `getBlindSpotActiveFps` (replace its body to delegate):

```kotlin
@JvmStatic fun blindSpotActiveFps(bs: org.json.JSONObject): Int =
    bs.optInt("activeFps", 25).coerceIn(1, 30)
@JvmStatic fun blindSpotContrast(bs: org.json.JSONObject): Float =
    bs.optDouble("contrast", 1.0).toFloat().coerceIn(0.5f, 2.0f)
@JvmStatic fun blindSpotSharpen(bs: org.json.JSONObject): Float =
    bs.optDouble("sharpen", 0.0).toFloat().coerceIn(0.0f, 1.0f)

fun getBlindSpotActiveFps(): Int = blindSpotActiveFps(getBlindSpot())
fun getBlindSpotContrast(): Float = blindSpotContrast(getBlindSpot())
fun getBlindSpotSharpen(): Float = blindSpotSharpen(getBlindSpot())
```

Then bump the default and add the new keys in the blindspot defaults block (the `if (!blindspot.has(...)) blindspot.put(...)` list, ~658-664):

```kotlin
if (!blindspot.has("activeFps")) blindspot.put("activeFps", 25)   // was 15
if (!blindspot.has("contrast")) blindspot.put("contrast", 1.0)
if (!blindspot.has("sharpen")) blindspot.put("sharpen", 0.0)
```

Note: existing configs that already persisted `activeFps=15` keep 15 (the `has` guard). That is fine — the default lifts new/unset installs to 25; the on-car config will be set to 25 explicitly during Task 6.

- [ ] **Step 4: Run test to verify it passes**

Run: same command as Step 2.
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt app/src/test/java/com/overdrive/app/config/BlindSpotConfigTest.kt
git commit -m "feat(blindspot): add contrast/sharpen config + raise activeFps default to 25"
```

---

### Task 2: Shader — clarity uniforms + contrast/sharpen in odBlend

**Files:**
- Modify: `app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java` (uniform fields ~40-103; `glGetUniformLocation` block ~289-317; `glUniform` upload block ~400-455; `setBlindSpotParams` ~776-802; shader source `buildFragmentShader` — `odBlend` ends ~1037 with `return vec4(mix(a, b, wB).rgb, cov);`)

**Interfaces:**
- Consumes: `UnifiedConfigManager.getBlindSpotContrast()`, `getBlindSpotSharpen()` (Task 1) — but this task only stores values passed in; the pipeline wires config in Task 3.
- Produces: `public void setBlindSpotClarity(float contrast, float sharpen)` on `GpuStreamScaler` — stores into `bsContrast`/`bsSharpen` fields (defaults `1.0f`/`0.0f`) and marks uniforms dirty.

- [ ] **Step 1: Read the exact odBlend structure before editing**

Run: `grep -n "float odBlend\|texture2D(uCameraTex\|return vec4(mix\|uBsMergeMode\|uOd4.z, uOd4.x\|uOd3.z, uOd3.x" app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java`
Purpose: confirm the two `texture2D(uCameraTex, odMap(...))` tap calls (tap A → `uOd4.*`, tap B → `uOd3.*`) and the merge-mode guards, so the sharpen samples the taps that are actually active. No code change in this step.

- [ ] **Step 2: Add uniform fields and default clarity fields**

Near the other `uBs*Location` fields (~40) add:
```java
private int uBsContrastLocation = -1;
private int uBsSharpenLocation = -1;
```
Near `odCoef` (~104) add:
```java
private volatile float bsContrast = 1.0f;   // neutral
private volatile float bsSharpen  = 0.0f;   // off
```

- [ ] **Step 3: Locate the uniforms at link time**

In the `glGetUniformLocation` block (~289-317, next to `uBsRadius`):
```java
uBsContrastLocation = GLES20.glGetUniformLocation(programId, "uBsContrast");
uBsSharpenLocation  = GLES20.glGetUniformLocation(programId, "uBsSharpen");
```

- [ ] **Step 4: Upload the uniforms each draw**

In the uniform-upload block (~400-455, alongside `uBsRadius`), inside the same `bs` (view 7/8) guard:
```java
if (uBsContrastLocation >= 0) GLES20.glUniform1f(uBsContrastLocation, bs ? bsContrast : 1.0f);
if (uBsSharpenLocation  >= 0) GLES20.glUniform1f(uBsSharpenLocation,  bs ? bsSharpen  : 0.0f);
```
(Passing neutral values on non-BS views keeps them inert everywhere.)

- [ ] **Step 5: Add the setter**

Near `setBlindSpotParams` (~776):
```java
/** Blind-spot card clarity (views 7/8): contrast pivot (1.0 = neutral) and unsharp
 *  amount (0.0 = off). Applied to the stitched colour before the card framing. */
public void setBlindSpotClarity(float contrast, float sharpen) {
    this.bsContrast = Math.max(0.5f, Math.min(2.0f, contrast));
    this.bsSharpen  = Math.max(0.0f, Math.min(1.0f, sharpen));
    this.uniformsDirty.set(true);
}
```

- [ ] **Step 6: Declare the uniforms in the shader source**

In `buildFragmentShader`, next to `"uniform float uBsRadius;\n"` (~1046):
```java
"uniform float uBsContrast;\n" +
"uniform float uBsSharpen;\n" +
```
If the shader does not already enable derivatives, add at the very top of the fragment source (after the `precision` line): `"#extension GL_OES_standard_derivatives : enable\n"`. (Adreno 610 supports it. If a compile error appears at Step 8, replace the `fwidth(...)` in Step 7 with a fixed offset `vec2 duv = vec2(0.0015, 0.0015);`.)

- [ ] **Step 7: Apply sharpen + contrast at the odBlend return**

Replace the final `return vec4(mix(a, b, wB).rgb, cov);` with the block below. Capture the tap UVs at the two `texture2D(uCameraTex, odMap(...))` calls first — i.e. change:
```glsl
a = texture2D(uCameraTex, odMap(cA, rectSize, fA, pixAspect, th,        uOd2.x, yOut, 1.0, uOd4.z, uOd4.x, uOd4.y));
```
to
```glsl
vec2 uvA = odMap(cA, rectSize, fA, pixAspect, th,        uOd2.x, yOut, 1.0, uOd4.z, uOd4.x, uOd4.y);
a = texture2D(uCameraTex, uvA);
```
and likewise `uvB` for tap B. Then the return becomes:
```glsl
vec3 col = mix(a, b, wB).rgb;
if (uBsSharpen > 0.0) {
    vec2 uvP = mix(uvA, uvB, wB);
    vec2 duv = fwidth(uvP) * 1.5;
    vec3 blur = 0.25 * (
        texture2D(uCameraTex, uvP + vec2(duv.x, 0.0)).rgb +
        texture2D(uCameraTex, uvP - vec2(duv.x, 0.0)).rgb +
        texture2D(uCameraTex, uvP + vec2(0.0, duv.y)).rgb +
        texture2D(uCameraTex, uvP - vec2(0.0, duv.y)).rgb);
    col += uBsSharpen * (col - blur);
}
col = clamp((col - 0.5) * uBsContrast + 0.5, 0.0, 1.0);
return vec4(col, cov);
```
At `uBsSharpen==0.0` (default) the block is skipped and `uBsContrast==1.0` makes the contrast line an identity, so this is bit-identical to the current shader by default.

- [ ] **Step 8: Compile-verify (shader compiles at runtime, so verify the build + assert defaults are neutral)**

Run: `docker run --rm -v "$PWD":/work -v and-gradle:/root/.gradle -v and-sdk:/opt/android-sdk -v /home/shaunes/.overdrive-build/android:/root/.android -w /work overdrive-build:latest ./gradlew :app:assembleDebug --offline`
Expected: BUILD SUCCESSFUL. (GLSL compiles on-device; correctness is verified in Task 6. Java-side compile catches uniform/field typos.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java
git commit -m "feat(blindspot): shader contrast + guarded unsharp on the stitched card (neutral by default)"
```

---

### Task 3: Pipeline — feed config clarity into the scaler

**Files:**
- Modify: `app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java` (where `setBlindSpotParams` is called on the stream scaler — grep for it)

**Interfaces:**
- Consumes: `GpuStreamScaler.setBlindSpotClarity(float, float)` (Task 2), `UnifiedConfigManager.getBlindSpotContrast()/getBlindSpotSharpen()` (Task 1).
- Produces: `public void setBlindSpotClarity(float contrast, float sharpen)` on `GpuSurveillancePipeline` that forwards to the scaler; and a call that applies the config values wherever the geometry params are applied.

- [ ] **Step 1: Find where geometry params are pushed to the scaler**

Run: `grep -n "setBlindSpotParams\|streamScaler\b\|bsScaler" app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java`
Purpose: locate the scaler reference and the method(s) that apply BS params on enable and on live update — the clarity call goes in the same place(s).

- [ ] **Step 2: Add a forwarding method + apply config on the same path**

Add:
```java
/** Forward blind-spot card clarity to the stream scaler (views 7/8). */
public void setBlindSpotClarity(float contrast, float sharpen) {
    GpuStreamScaler s = /* the same scaler ref used by setBlindSpotParams */;
    if (s != null) s.setBlindSpotClarity(contrast, sharpen);
}
```
Wherever the pipeline applies the geometry params from config (the method found in Step 1, e.g. when the BS lane is enabled/resynced), add a line right after the geometry apply:
```java
s.setBlindSpotClarity(
    com.overdrive.app.config.UnifiedConfigManager.getBlindSpotContrast(),
    com.overdrive.app.config.UnifiedConfigManager.getBlindSpotSharpen());
```

- [ ] **Step 3: Compile-verify**

Run: `... ./gradlew :app:assembleDebug --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java
git commit -m "feat(blindspot): apply configured card clarity when the lane arms"
```

---

### Task 4: API — accept contrast/sharpen for live tuning

**Files:**
- Modify: `app/src/main/java/com/overdrive/app/server/StreamingApiHandler.java` (`handleBlindSpotParams`, ~916-970)

**Interfaces:**
- Consumes: `GpuSurveillancePipeline.setBlindSpotClarity(float, float)` (Task 3).
- Produces: `/api/stream/bs/...` accepts optional `contrast` and `sharpen` query/body params; response echoes them.

- [ ] **Step 1: Parse the two params and forward them**

In `handleBlindSpotParams`, after the existing geometry params are parsed and `pipeline.setBlindSpotParams(...)` is called, add (parse with the same helper the other params use; default to the current config value so an omitted param is a no-op):
```java
float contrast = parseFloatParam(tail, "contrast", UnifiedConfigManager.getBlindSpotContrast());
float sharpen  = parseFloatParam(tail, "sharpen",  UnifiedConfigManager.getBlindSpotSharpen());
pipeline.setBlindSpotClarity(contrast, sharpen);
ok.put("contrast", contrast);
ok.put("sharpen", sharpen);
```
(Use whatever param-parse idiom the surrounding code already uses for `hfov` etc.; `parseFloatParam` is shorthand for that existing pattern.)

- [ ] **Step 2: Compile-verify**

Run: `... ./gradlew :app:assembleDebug --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/overdrive/app/server/StreamingApiHandler.java
git commit -m "feat(blindspot): accept contrast/sharpen on /api/stream/bs for live tuning"
```

---

### Task 5: Web editor — two clarity sliders

**Files:**
- Modify: `app/src/main/assets/web/shared/road-sense.js` (BS editor: `_bsSetSlider` ~380, the bs param load ~178-183, and the POST that sends params to `/api/stream/bs`)

**Interfaces:**
- Consumes: the `/api/stream/bs` `contrast`/`sharpen` params (Task 4).

- [ ] **Step 1: Add slider state + load**

Where the BS config is read into `c.bs*` (near `c.bsSideFov`, ~178), add:
```javascript
if (typeof bs.contrast === 'number') c.bsContrast = this._clamp(bs.contrast, 0.5, 2.0);
if (typeof bs.sharpen === 'number')  c.bsSharpen  = this._clamp(bs.sharpen, 0.0, 1.0);
```

- [ ] **Step 2: Wire two sliders**

Following the existing `_bsSetSlider(sliderId, labelId, value)` pattern used for the geometry sliders, add two sliders (`bsContrast` 0.5–2.0 step 0.05 default 1.0; `bsSharpen` 0.0–1.0 step 0.05 default 0.0) to the BS editor markup, and include `contrast`/`sharpen` in the object POSTed to `/api/stream/bs` on change and on Save. Match the exact DOM/markup convention the other BS sliders use (copy one geometry slider block and rename).

- [ ] **Step 3: Build-verify (assets bundle into the APK)**

Run: `... ./gradlew :app:assembleDebug --offline`
Expected: BUILD SUCCESSFUL (web assets are packaged, not compiled; this just confirms the build still assembles).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/web/shared/road-sense.js
git commit -m "feat(blindspot): contrast + sharpen sliders in the blind-spot editor"
```

---

### Task 6: On-device acceptance (fps + visual A/B)

**Files:** none (verification only). Uses the running car over tailscale.

- [ ] **Step 1: Full build + unit tests**

Run: `... ./gradlew :app:testDebugUnitTest :app:assembleDebug --offline`
Expected: BUILD SUCCESSFUL, 0 test failures (includes `BlindSpotConfigTest`).

- [ ] **Step 2: Install + redeploy daemon (new APK path)**

```bash
SP=<scratch>; cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk "$SP/bs.apk"
docker run -d --name byd-adb-live -v /home/shaunes/.android:/root/.android -v "$SP":/sp --entrypoint sleep byd-adb infinity
docker exec byd-adb-live adb connect 100.68.86.26:5555
docker exec byd-adb-live adb -s 100.68.86.26:5555 install -r /sp/bs.apk
# redeploy daemon so it loads the new APK (see memory: reinstall leaves a stale watchdog CLASSPATH)
docker exec byd-adb-live adb -s 100.68.86.26:5555 shell 'WD=$(cat /data/local/tmp/cam_watchdog.pid); [ -n "$WD" ] && kill $WD; ps -A -o PID,NAME | awk "\$2==\"byd_cam_daemon\"{print \$1}" | while read p; do kill $p; done; rm -f /data/local/tmp/camera_daemon.lock; am force-stop com.overdrive.app; sleep 2; am start -n com.overdrive.app/.ui.MainActivity; sleep 65'
```
Expected: `grep start_cam_daemon.sh` APK hash == `pm path`.

- [ ] **Step 3: Verify fps = 25 on reveal**

Requires the vehicle in READY (ACC on — the BS lane gates on `isAccOn()`).
```bash
docker exec byd-adb-live adb -s 100.68.86.26:5555 shell 'curl -s -X POST http://127.0.0.1:8080/api/bs/enable; curl -s -X POST http://127.0.0.1:8080/api/bs/view/7; sleep 3; grep "cam fps=" /data/local/tmp/cam_daemon.log | tail -2'
```
Expected: a `recording:PROXIMITY (cam fps=25 …+BS)` line (was 15).

- [ ] **Step 4: Visual A/B (phone photo — screencap can't read the overlay)**

With BS enabled and the vehicle in READY:
1. Photograph the card at defaults (contrast 1.0, sharpen 0.0) — reference.
2. Dial via API and photograph each:
   ```bash
   docker exec byd-adb-live adb -s 100.68.86.26:5555 shell 'curl -s -X POST "http://127.0.0.1:8080/api/stream/bs/params?contrast=1.15&sharpen=0.35"'
   ```
3. Compare photos: contrast/sharpen visibly increase clarity without haloing/false edges. Pick values that look right; leave the config at them (or revert to 1.0/0.0).
Expected: default photo == current behaviour; dialed photo is sharper/punchier with no artifacts.

- [ ] **Step 5: Restore + clean up**

```bash
docker exec byd-adb-live adb -s 100.68.86.26:5555 shell 'curl -s -X POST http://127.0.0.1:8080/api/bs/disable'
docker rm -f byd-adb-live
```
Confirm daemons healthy before disconnecting.

- [ ] **Step 6: Push the branch + open the fork PR**

```bash
git push -u fork feat/blindspot-clarity-fps
gh pr create --repo shauneccles/Overdrive-release --base main --head feat/blindspot-clarity-fps --title "feat(blindspot): 25fps reveal + tunable contrast/sharpen" --body "<summary + the fps and A/B photo evidence>"
```

---

## Self-Review

**Spec coverage:**
- Part A (fps 15→25): Task 1 (default) + Task 6 Step 3 (verify). ✓
- Part B config keys + getters: Task 1. ✓
- Part B plumbing (setter, uniforms): Task 2 (scaler), Task 3 (pipeline). ✓
- Part B shader contrast+sharpen: Task 2 Steps 6-7. ✓
- Part B live tuning (API + sliders): Task 4 + Task 5. ✓
- Part C verification (defaults neutral, on-device A/B, safety caps): Task 1 tests + Task 6. ✓
- Neutral-by-default guarantee: enforced in Task 1 defaults, Task 2 Step 7 guard, Global Constraints. ✓

**Placeholder scan:** Two integration points are intentionally "read first, then adapt" (Task 2 Step 1, Task 3 Step 1, Task 5 Step 2) because shader-internal variable scoping and the exact web markup can't be reproduced blind — each provides the full code to insert and the exact grep to place it. `parseFloatParam` (Task 4) is named as shorthand for the existing param-parse idiom, to be matched to the surrounding code. No TBD/TODO/"handle edge cases".

**Type consistency:** `setBlindSpotClarity(float contrast, float sharpen)` is identical across Task 2 (scaler), Task 3 (pipeline), Task 4 (caller). Config helper names (`blindSpotActiveFps/Contrast/Sharpen`, `getBlindSpot*`) are consistent between Task 1 definition and Tasks 3/4 use. Uniform names `uBsContrast`/`uBsSharpen` consistent Task 2 Steps 2-7.
