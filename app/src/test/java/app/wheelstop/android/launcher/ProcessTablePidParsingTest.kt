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

    /**
     * A daemon's own argv is just its nice-name. Its launcher/watchdog is a
     * shell whose command line MENTIONS that name, so a substring match picks
     * up both. Feeding a shell's pid to the stale-daemon detector produced
     * "UNKNOWN sentry_daemon pid=13194 — environ unreadable" every 30s on the
     * car: the shell has no CLASSPATH of its own, so it can never classify.
     */
    @Test
    fun skipsTheWatchdogShellThatLaunchesTheDaemon() {
        val withWatchdog = """
            13194 sh -c CLASSPATH=/data/app/app.wheelstop.android-abc==/base.apk app_process /system/bin --nice-name=sentry_daemon app.wheelstop.android.daemon.SentryDaemon
            13195 sentry_daemon
        """.trimIndent()
        assertEquals(listOf(13195), DaemonLauncher.pidsFor(withWatchdog, "sentry_daemon"))
    }

    @Test
    fun skipsTheLaunchShellForTheCameraDaemon() {
        // The camera daemon's own watchdog SCRIPT (`sh .../start_cam_daemon.sh`)
        // never mentions "byd_cam_daemon", so it was never a false match. Its
        // direct-launch shell does, and that is the form that needs excluding.
        val withLaunchShell = """
            1300 sh -c CLASSPATH=/data/app/app.wheelstop.android-abc==/base.apk app_process /system/bin --nice-name=byd_cam_daemon app.wheelstop.android.daemon.CameraDaemon
            1161 byd_cam_daemon
        """.trimIndent()
        assertEquals(listOf(1161), DaemonLauncher.pidsFor(withLaunchShell, "byd_cam_daemon"))
    }

    @Test
    fun stillFindsTheDaemonWhenNoWatchdogIsPresent() {
        assertEquals(listOf(1161), DaemonLauncher.pidsFor("1161 byd_cam_daemon", "byd_cam_daemon"))
    }
}
