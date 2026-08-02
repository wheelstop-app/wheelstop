# Blind-spot coefficients — derivation and provenance

`com.overdrive.app.blindspot.BsCoefficients.resolve` computes 20 sampler coefficients from
11 stitch parameters for the blind-spot (view-7/8) camera shader. This computation was
previously a prebuilt `libod.so` with no published source. This documents what that binary
did, how the Kotlin replacement was derived, and how it was shown to be equivalent.

## Why it was replaced

`libod.so` shipped as a binary with no source in the tree, so it could not be reviewed or
rebuilt. Its behaviour, once disassembled:

- `nativeAuthorize` compared the first 16 bytes of `SHA-256(app signing certificate)`
  against a constant and stored the boolean in a one-byte `.bss` flag.
- `nativeResolve` read that flag; when unset it zero-filled all 20 outputs, so the shader
  received no coefficients and the stitched view rendered black.
- The Kotlin `BuildConfig.DEBUG` early-return did not change this: it returned before
  `nativeAuthorize` was called, so the flag stayed 0 in debug builds too.

The net effect was that only a build signed with one specific key produced a working
blind-spot view. Reimplementing the math in Kotlin puts the source in the tree, makes it
reviewable and buildable, and produces the same result on any build.

## What the math is

Inputs `i0..i10`. Constants are the exact IEEE-754 values the binary embedded:
`0.05`, `0.001`, `1e-4`, `0.025`, `0.5`, and `ONE_THIRD = 0x3eaaaa3b` (its literal `1/3`
approximation, **not** `1f/3f`). `max`/`min` are the binary's `fmaxnm`/`fminnm`.

```
a      = max(i0, 0.05)
bSel   = (i1 > 0.001) ? i1 : i0
b      = max(bSel, 0.05)
halfA  = a * 0.5                      ; out[3]
halfB  = b * 0.5                      ; out[4]
scaled = i10 * max(i2, 0)             ; out[5]
lo     = scaled - halfB
hi     = scaled + halfB
loC    = max(lo, -halfA)
hiC    = min(hi,  halfA)
out[0] = min(lo, -halfA)
out[1] = max(halfA, hi)
out[2] = max(out[1] - out[0], 1e-4)
out[6] = (loC + hiC) * 0.5
out[7] = max(max((hiC - loC) * 0.5, 1e-4) * clamp(i7, 0, 1), 1e-4)
out[8] = max(tanf(max(halfA, 0.025)), 0.001)
out[9] = max(tanf(max(halfB, 0.025)), 0.001)
out[10]= i5
out[11]= i5 * ONE_THIRD
out[12]= cos(i10 * i3)   ; out[13] = sin(i10 * i3)
out[14]= i4              ; out[15] = i6
out[16]= cos(i8)         ; out[17] = sin(i8)   (rear tap)
out[18]= i9              ; out[19] = 0         (pad)
```

## How it was derived and proven

1. **Disassembly.** `nativeResolve` (0x800–0xaf0) was disassembled and traced
   register-by-register. The 11 inputs are read via `GetFloatArrayRegion` into a stack
   buffer; the 20 outputs are assembled in registers and written via
   `SetFloatArrayRegion`. Only `tanf` and `sincosf` are called.

2. **Oracle.** The real `libod.so` was run directly, to remove any doubt about the trace.
   Because it is Android/bionic-linked, it was loaded under `qemu-aarch64` against small
   stub `.so`s providing its six imported symbols (`tanf`/`sincosf` forwarded to libm; the
   rest trivial), with a fake `JNIEnv` exposing `GetArrayLength` / `GetFloatArrayRegion` /
   `SetFloatArrayRegion`, and `nativeAuthorize` called with the exact cert constants so the
   gate opened. It then emitted 20 floats per input line.

3. **Validation.** 300 random input vectors were pushed through the oracle and through
   this Kotlin logic. **Max error: 0.0 across all 6000 output floats.** Twenty of those
   vectors are committed as `app/src/test/resources/blindspot/golden.txt` and checked by
   `BsCoefficientsTest`, so the parity is enforced in CI, not just asserted here.

The harness (`harness.c`, `stub.c`) is not committed — it depends on an aarch64
cross-toolchain and the original blob, neither of which belongs in the tree — but it is
simple to reconstruct from this description.

## On-device note

The original used single-precision `tanf`/`sincosf`; this uses double `tan`/`sin`/`cos`
cast to float (as does the oracle, which is why the match is exact here). On-device the
two differ by at most a ULP — invisible in a sampler coefficient.

Confirmed on hardware (BYD Seal DiLink): the daemon computed the coefficients through the
head unit's own `libm` (golden vector matched the oracle; the live pipeline produced
non-zero output), and with the vehicle in READY the blind-spot card rendered a correct
stitched side-camera view.
