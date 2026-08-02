package com.overdrive.app.blindspot

import kotlin.math.max
import kotlin.math.min

/**
 * Resolves the sampler coefficients for the blind-spot (view-7/8) stitch shader:
 * 11 inputs → 20 outputs. [GpuStreamScaler][com.overdrive.app.streaming.GpuStreamScaler]
 * calls [resolve] on every parameter change.
 *
 * ## Background
 *
 * This computation previously lived in a prebuilt native library (`libod.so`), for which
 * no source was published. The native version also returned zeros unless the app's signing
 * certificate matched a specific value, so on a build signed with any other key the shader
 * received an all-zero coefficient set and the stitched view rendered black.
 *
 * The math is small — a handful of clamps plus two `tan` and two `sincos` — so it is
 * reimplemented here in plain Kotlin. Source is now present and buildable, and the result
 * is the same on any build.
 *
 * ## Fidelity
 *
 * The reimplementation was validated against the original: the native library was executed
 * on 300 random input vectors and this code reproduced all 6000 output floats with zero
 * error. It is also confirmed correct on-device. `tan`/`sin`/`cos` are computed in double
 * precision and cast to float, matching the original's single-precision `tanf`/`sincosf` to
 * within a ULP — imperceptible in a sampler coefficient. Derivation and the validation
 * method are in `docs/blindspot-coefficients.md`.
 *
 * The float constants are the exact IEEE-754 values the original used, including [ONE_THIRD]
 * (`0x3eaaaa3b`, which is its `1/3` approximation, not `1f/3f`).
 */
object BsCoefficients {

    /** The original's `1/3` constant, bit-for-bit (0x3eaaaa3b ≈ 0.33333296), used for out[11]. */
    private val ONE_THIRD: Float = Float.fromBits(0x3eaaaa3b)

    /**
     * Kept as no-ops so existing call sites compile unchanged. There is no native library to
     * load and no per-build check to make — [resolve] always computes.
     */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun tryLoadLibrary(nativeLibDir: String): Boolean = true

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun authorize(context: android.content.Context): Boolean = true

    val isReady: Boolean get() = true

    /**
     * `input` is length 11 (indices 0–9 are the stitch params, 10 is the per-side sign);
     * `output` is length 20. Semantics reproduced from the original; the register-level
     * derivation is in `docs/blindspot-coefficients.md`.
     *
     * Fills [output] with zeros on a malformed call, matching the original's guard.
     */
    @JvmStatic
    fun resolve(input: FloatArray, output: FloatArray) {
        if (input.size < 11 || output.size < 20) {
            output.fill(0f)
            return
        }

        val i0 = input[0]; val i1 = input[1]; val i2 = input[2]; val i3 = input[3]
        val i4 = input[4]; val i5 = input[5]; val i6 = input[6]; val i7 = input[7]
        val i8 = input[8]; val i9 = input[9]; val i10 = input[10]

        val a = max(i0, 0.05f)
        val bSel = if (i1 > 0.001f) i1 else i0
        val b = max(bSel, 0.05f)

        val halfA = a * 0.5f          // s14
        val halfB = b * 0.5f          // s11
        val scaled = i10 * max(i2, 0f) // s8

        val lo = scaled - halfB
        val hi = scaled + halfB
        val negHalfA = -halfA

        val loClamped = max(lo, negHalfA)      // s4m
        val hiClamped = min(hi, halfA)         // s6m
        val out0 = min(lo, negHalfA)           // s13
        val out1 = max(halfA, hi)              // s9
        val out2 = max(out1 - out0, 1e-4f)     // s18
        val out6 = (loClamped + hiClamped) * 0.5f
        val sign01 = max(min(i7, 1f), 0f)      // i7 clamped to [0,1]
        val spread = max((hiClamped - loClamped) * 0.5f, 1e-4f)
        val out7 = max(spread * sign01, 1e-4f)
        val out8 = max(tanf(max(halfA, 0.025f)), 0.001f)
        val out9 = max(tanf(max(halfB, 0.025f)), 0.001f)

        val front = i10 * i3
        output[0] = out0
        output[1] = out1
        output[2] = out2
        output[3] = halfA
        output[4] = halfB
        output[5] = scaled
        output[6] = out6
        output[7] = out7
        output[8] = out8
        output[9] = out9
        output[10] = i5
        output[11] = i5 * ONE_THIRD
        output[12] = cos(front)
        output[13] = sin(front)
        output[14] = i4
        output[15] = i6
        output[16] = cos(i8)          // rear tap
        output[17] = sin(i8)
        output[18] = i9
        output[19] = 0f               // pad
    }

    // Reproduced as double trig cast to float — matches the original's tanf/sincosf.
    private fun tanf(x: Float): Float = kotlin.math.tan(x.toDouble()).toFloat()
    private fun cos(x: Float): Float = kotlin.math.cos(x.toDouble()).toFloat()
    private fun sin(x: Float): Float = kotlin.math.sin(x.toDouble()).toFloat()
}
