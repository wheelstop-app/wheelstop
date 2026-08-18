package app.wheelstop.android.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `pidsFor` reads the same snapshot `processAliveIn` does, so the two must agree
 * about what counts as a match — in particular the sentry/acc_sentry distinction,
 * where a substring match on "sentry_daemon" also matches "acc_sentry_daemon".
 */
class ProcessTablePidParsingTest {

    private val snapshot = """
        PID ARGS
        1 /init
        1161 byd_cam_daemon
        1200 sentry_daemon
        1201 acc_sentry_daemon
        1300 sh /data/local/tmp/start_cam_daemon.sh
    """.trimIndent()

    @Test
    fun findsPidForASimpleProcessName() {
        assertEquals(listOf(1161), DaemonLauncher.pidsFor(snapshot, "byd_cam_daemon"))
    }

    @Test
    fun sentryDoesNotMatchAccSentry() {
        assertEquals(listOf(1200), DaemonLauncher.pidsFor(snapshot, "sentry_daemon"))
    }

    @Test
    fun accSentryMatchesOnlyItself() {
        assertEquals(listOf(1201), DaemonLauncher.pidsFor(snapshot, "acc_sentry_daemon"))
    }

    @Test
    fun returnsEveryPidWhenADaemonIsDuplicated() {
        val dup = "1200 sentry_daemon\n1500 sentry_daemon"
        assertEquals(listOf(1200, 1500), DaemonLauncher.pidsFor(dup, "sentry_daemon"))
    }

    @Test
    fun missingProcessYieldsEmpty() {
        assertEquals(emptyList<Int>(), DaemonLauncher.pidsFor(snapshot, "telegram_bot_daemon"))
    }

    @Test
    fun ignoresTheHeaderAndUnparseableLines() {
        assertEquals(emptyList<Int>(), DaemonLauncher.pidsFor("PID ARGS\nnotanumber foo", "foo"))
    }
}
