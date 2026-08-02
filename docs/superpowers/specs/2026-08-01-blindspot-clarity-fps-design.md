# Blind-spot clarity + frame-rate — design

**Date:** 2026-08-01
**Status:** approved (design), pending spec review

## Goal

Improve the visible quality of the blind-spot (view-7/8) camera card, now that the
coefficient math is open Kotlin (`com.overdrive.app.blindspot.BsCoefficients`) rather than
a locked native blob. Two independent, low-risk changes:

1. **Frame rate:** raise the blind-spot reveal rate from 15 → 25 fps for smoother moving
   traffic.
2. **Clarity:** add a contrast lift and a subtle sharpen to the stitched card image,
   tunable live on the car.

Non-goal: changing the stitch *geometry* (the 11 calibration params). That is the safety
surface — object position/distance — and it is deliberately left alone. `BsCoefficients`
itself is not touched.

## Guiding principle: neutral by default

Every new lever ships at an exact no-op value, so the blind-spot output is byte-for-byte
identical to today until the operator opts in. This keeps the emulator-verified,
bit-exact pipeline unchanged by default, makes the change trivially revertible (one value),
and means CI/unit behaviour is unaffected.

## Part A — reveal frame rate 15 → 25

**Current state (verified in code):** the reveal fps is already config-driven.
`RecordingModeManager`'s PROXIMITY reconcile branch computes
`bsDemand = (bsKeepWarmActive() && bsViewShown()) ? UnifiedConfigManager.getBlindSpotActiveFps() : 0`
and the shared-camera HAL rate is `max(proxFps, streamFps, bsDemand)`.
`getBlindSpotActiveFps()` reads `blindspot.activeFps` (default 15, coerced to 1..30).

**Change:** set the `blindspot.activeFps` default from **15 → 25** in the two places it is
defined:
- the config initializer in `UnifiedConfigManager` (`blindspot.put("activeFps", …)`),
- the getter fallback `getBlindSpot().optInt("activeFps", …)`.

25 is in the HAL-supported set `{8, 15, 25}` for this device. No new wiring — the key is
already live.

**Accepted side effect:** the camera HAL rate is global (shared by recorder, stream,
blind-spot). During a turn-signal reveal the whole pipeline briefly runs at 25 fps, so the
recorder writes at 25 fps for the duration of the reveal. The vehicle is powered during a
reveal, so power/thermal is a non-issue. Idle (hidden BS) rate is unchanged (~1 fps).

**Risk:** low. It is a value change to an existing, tested config path. The reconcile
logic itself is not modified.

## Part B — contrast + subtle sharpen (tunable, off by default)

### Config
Two new keys in the `blindspot` config section, both exact no-ops at their defaults:
- `blindspot.contrast` — default **1.0** (neutral; `(c−0.5)·1+0.5 == c`).
- `blindspot.sharpen` — default **0.0** (off).

With getters `getBlindSpotContrast()` / `getBlindSpotSharpen()`, clamped to conservative
ranges (contrast ≈ `0.5..2.0`, sharpen ≈ `0.0..1.0`) so neither can produce extreme
false-edge output on a safety camera.

### Plumbing (mirrors the existing geometry params)
- Add a **new** setter `GpuStreamScaler.setBlindSpotClarity(contrast, sharpen)` rather than
  extending `setBlindSpotParams(...)` — the latter has multiple callers and a fixed
  positional signature, so a sibling setter is a smaller, clearer change.
- Two new fragment-shader uniforms `uBsContrast`, `uBsSharpen`, resolved and uploaded
  alongside the existing `uOd0..4` / `uBsRadius` etc.
- The values flow from config → pipeline → scaler on load and on live update, the same way
  the geometry params already do.

### Shader
Applied to the **stitched video colour** produced by `odBlend()`, *before* the curved-card
framing/lighting/mask — so the card bevel and coverage are untouched.

- **Contrast** (per-pixel, no extra fetches):
  `rgb = clamp((rgb − 0.5) * uBsContrast + 0.5, 0.0, 1.0)`.
- **Sharpen** (subtle unsharp mask): when `uBsSharpen > 0`, take 4 extra `uCameraTex`
  samples around each tap's mapped source coordinate and form `localBlur`, then
  `rgb += uBsSharpen * (rgb − localBlur)`, clamped. The neighbour offset uses screen-space
  derivatives of the source UV (`dFdx`/`dFdy`) so the step is correct in *source* space
  despite the fisheye warp; this needs `GL_OES_standard_derivatives` (present on this
  Adreno — the plan will confirm the `#extension` directive compiles, and fall back to a
  small fixed source-space offset if not). When `uBsSharpen == 0` the whole block is
  skipped by a guard, so the default path takes no extra samples and is bit-identical.

GPU cost at non-zero sharpen: a few extra texture fetches on the BS card only, which runs
at ≤25 fps over a 768×576 region — negligible on the Adreno.

### Live tuning
- Extend the existing `/api/stream/bs/` params endpoint (`handleBlindSpotParams`) to accept
  `contrast` and `sharpen`, in-memory live like the other sliders.
- Two sliders in the RoadSense Blind-Spot debug editor (`road-sense.js`), following the
  existing `_bsSetSlider` pattern; Save persists to the `blindspot` config section like the
  geometry sliders.

## Part C — verification & safety

- **Defaults = no change.** At `contrast=1.0, sharpen=0.0` the card renders identically to
  today; `BsCoefficients` is untouched, so its golden-vector test and the bit-exact
  guarantee stand. A debug build at defaults produces the same blind-spot image as the
  current build.
- **On-device, judged by eye (no oracle):** enable BS on the head unit, photograph the card
  at defaults (identical), then dial `contrast`/`sharpen` live via the sliders or the API
  and re-photograph until it looks right; persist. Reverting is `contrast=1.0`/`sharpen=0.0`.
- **fps:** confirm on-car that a reveal now runs at 25 (daemon log `cam fps=25 …+BS`), and
  that idle/recording rates are unchanged.
- **Safety guards:** sharpen defaults off and is hard-capped; contrast is range-clamped;
  fps is pure smoothness with no geometry change; nothing is applied to the recorder lane
  or any non-BS view.

## Scope (YAGNI)

- Reuse `setBlindSpotParams` / `/api/stream/bs` / the `road-sense.js` editor — no parallel
  plumbing.
- No changes to `BsCoefficients`, the coefficient math, the recorder, or other views.
- No new camera profiles; reuse the existing `activeFps` path.

## Files expected to change

- `app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt` — activeFps default
  15→25; add `contrast`/`sharpen` defaults + getters.
- `app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java` — clarity setter, two
  uniforms, shader contrast+sharpen.
- `app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java` — pass
  clarity params through to the scaler (same path as geometry).
- `app/src/main/java/com/overdrive/app/server/StreamingApiHandler.java` — accept
  `contrast`/`sharpen` in `handleBlindSpotParams`.
- `app/src/main/assets/web/shared/road-sense.js` — two editor sliders.

## Open items resolved during design

- The reveal fps source: confirmed it reads `blindspot.activeFps` via
  `getBlindSpotActiveFps()` in RMM's PROXIMITY branch — so Part A is a default change, not a
  rewire.
