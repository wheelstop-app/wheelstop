package app.wheelstop.android.preflight

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure decision-table coverage for [ExclusivityPreflight.classify]. No ADB /
 * device access — the shell probes (probeInstalled/probeActive/check) that
 * feed this decision are exercised on-car, not here (see task-7-report.md's
 * on-car deferral).
 */
class ExclusivityPreflightTest {

    @Test
    fun notInstalled_isExclusive() {
        assertEquals(
            ExclusivityPreflight.Verdict.EXCLUSIVE,
            ExclusivityPreflight.classify(installed = false, active = false)
        )
    }

    @Test
    fun installedButDormant_isExclusive() {
        assertEquals(
            ExclusivityPreflight.Verdict.EXCLUSIVE,
            ExclusivityPreflight.classify(installed = true, active = false)
        )
    }

    @Test
    fun installedAndActive_isContended() {
        assertEquals(
            ExclusivityPreflight.Verdict.CONTENDED,
            ExclusivityPreflight.classify(installed = true, active = true)
        )
    }

    @Test
    fun notInstalledButActive_isExclusive() {
        // Defensive case: classify() is total and never invoked this way in
        // practice (check() short-circuits the active-probe when !installed),
        // but the pure function must still be sane on every input.
        assertEquals(
            ExclusivityPreflight.Verdict.EXCLUSIVE,
            ExclusivityPreflight.classify(installed = false, active = true)
        )
    }
}
