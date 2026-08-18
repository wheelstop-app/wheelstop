package app.wheelstop.android.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * All three core daemons must refuse to launch a second copy of themselves.
 *
 * CAMERA_DAEMON already did; SENTRY_DAEMON and ACC_SENTRY_DAEMON did not, and a
 * double app launch produced two sentry_daemon processes under two separate
 * watchdogs — two daemons contending for the same camera and sentinel files.
 *
 * Source inspection because launching needs a device, an adb channel and a real
 * process table. Same approach as the sibling *ContractTests.
 *
 * Routing SENTRY_DAEMON and ACC_SENTRY_DAEMON through the shared
 * isDaemonRunning() helper (rather than each daemon's own bespoke inline ps
 * check) introduced two regressions the presence check above cannot catch,
 * because it only asserts a call exists, not what the call actually matches:
 *
 *   1. isDaemonRunning had no acc_/word-boundary exclusion, so
 *      isDaemonRunning(SENTRY_DAEMON_PROCESS) could be masked by a live
 *      acc_sentry_daemon and never notice a dead sentry_daemon.
 *   2. isDaemonRunning only matches the daemon's own process line, so
 *      isDaemonRunning(ACC_SENTRY_DAEMON_PROCESS) alone lost the old check's
 *      watchdog-awareness — a watchdog up but not yet running its daemon
 *      would read as "not running" and get a second watchdog launched
 *      alongside it.
 *
 * The two tests below assert those specific properties so a future edit that
 * silently drops either one fails here instead of on a car.
 */
public class SingleInstanceGuardContractTest {

    private static final String LAUNCHER =
            "app/src/main/java/app/wheelstop/android/launcher/DaemonLauncher.kt";

    @Test
    public void everyCoreDaemonLaunchIsGuardedByIsDaemonRunning() throws IOException {
        String source = readRepositoryFile(LAUNCHER);
        for (String process : new String[]{
                "CAMERA_DAEMON_PROCESS", "SENTRY_DAEMON_PROCESS", "ACC_SENTRY_DAEMON_PROCESS"}) {
            // No trailing ")" required: ACC_SENTRY_DAEMON_PROCESS's call also
            // carries an alsoMatch argument (see accSentryGuardAlsoMatchesItsWatchdog
            // below), so its call site is "isDaemonRunning(ACC_SENTRY_DAEMON_PROCESS, ...".
            assertTrue(process + " must be guarded by isDaemonRunning before launch",
                    source.contains("isDaemonRunning(" + process));
        }
    }

    @Test
    public void isDaemonRunningExcludesAccSentryWhenCheckingForSentry() throws IOException {
        String source = readRepositoryFile(LAUNCHER);
        // Isolate the (processName, alsoMatch, callback) overload's own body —
        // "grep -v acc_" also appears elsewhere in this file (e.g.
        // verifySentryDaemonRunning's own probe), so a bare whole-file
        // contains() would pass even if isDaemonRunning itself never carried
        // the exclusion.
        String body = extractFunctionBody(source,
                "fun isDaemonRunning(processName: String, alsoMatch: String?, callback:");
        assertTrue("isDaemonRunning must special-case SENTRY_DAEMON_PROCESS — a live " +
                        "acc_sentry_daemon is a substring match for sentry_daemon and must " +
                        "not be allowed to mask a dead one",
                body.contains("processName == SENTRY_DAEMON_PROCESS"));
        assertTrue("isDaemonRunning's sentry branch must exclude acc_ from the match, " +
                        "the same way processAliveIn and pidsFor already do",
                body.contains("grep -v acc_"));
    }

    @Test
    public void accSentryGuardAlsoMatchesItsWatchdog() throws IOException {
        String source = readRepositoryFile(LAUNCHER);
        assertTrue("ACC_SENTRY_DAEMON_PROCESS's isDaemonRunning guard must also match its " +
                        "watchdog script (start_acc_sentry) — otherwise a watchdog that is up " +
                        "but hasn't spawned its daemon yet reads as \"not running\" and a " +
                        "second watchdog gets launched alongside it",
                source.contains(
                        "isDaemonRunning(ACC_SENTRY_DAEMON_PROCESS, alsoMatch = \"start_acc_sentry\")"));
    }

    /**
     * Slices out one top-level member function's source, from its declaration
     * line up to (but not including) the next top-level {@code fun}/
     * {@code private fun} at the same 4-space indentation. Deliberately naive
     * (no brace matching) — sufficient to keep an assertion scoped to a single
     * function without pulling in unrelated matches from the rest of the file.
     */
    private static String extractFunctionBody(String source, String declarationPrefix) {
        int start = source.indexOf(declarationPrefix);
        assertTrue("Could not find declaration \"" + declarationPrefix + "\" in " + LAUNCHER,
                start >= 0);
        int searchFrom = start + declarationPrefix.length();
        int nextFun = source.indexOf("\n    fun ", searchFrom);
        int nextPrivateFun = source.indexOf("\n    private fun ", searchFrom);
        int end = source.length();
        if (nextFun >= 0) end = Math.min(end, nextFun);
        if (nextPrivateFun >= 0) end = Math.min(end, nextPrivateFun);
        return source.substring(start, end);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
