package app.wheelstop.android.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Every core daemon launcher must be safe against being called twice at once.
 *
 * CORRECTION: earlier versions of this test (and the plan/spec it was
 * written from) claimed SENTRY_DAEMON and ACC_SENTRY_DAEMON had "no
 * single-instance guard." That was wrong — both already had their own inline
 * `ps` checks before this file's isDaemonRunning() consolidation, and
 * sentry's (`ps -A | grep -w $SENTRY_DAEMON_PROCESS | grep -v grep | grep -v
 * acc_`) correctly called callback.onLog()/onLaunched() before returning.
 * The consolidation onto a single isDaemonRunning() helper is still worth
 * keeping — three matchers that can no longer disagree, plus a correctness
 * fix that also benefits the camera and proxy callers — but it did not fix
 * the bug actually observed on a car.
 *
 * THE ACTUAL DEFECT: launchSentryDaemon was the one core launcher with no
 * concurrent-launch LATCH (see the guardHeld(...*LaunchStartedAt) calls
 * below). CAMERA_DAEMON, ACC_SENTRY_DAEMON, and TELEGRAM_DAEMON all
 * serialize concurrent calls to their own launch method with a self-healing
 * timestamped guard; sentry did not. Any ps-based "already running" check —
 * old or new — is ASYNCHRONOUS (check, await the shell callback, then
 * launch), so two overlapping launchSentryDaemon() calls could both observe
 * "not running" inside that window and both spawn a watchdog. That race, not
 * a missing ps check, produced the two live sentry_daemon processes observed
 * under two separate watchdogs, while CAMERA_DAEMON — which already had the
 * latch — only ever had one.
 *
 * Source inspection because launching needs a device, an adb channel and a real
 * process table. Same approach as the sibling *ContractTests.
 *
 * Routing SENTRY_DAEMON and ACC_SENTRY_DAEMON through the shared
 * isDaemonRunning() helper also introduced two smaller regressions that a
 * bare guard-presence check cannot catch, because it only asserts a call
 * exists, not what the call actually matches:
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
 * The tests below assert all three properties — the latch, and these two
 * matcher details — so a future edit that silently drops any of them fails
 * here instead of on a car.
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
    public void everyCoreDaemonLaunchIsLatchedAgainstConcurrentCalls() throws IOException {
        String source = readRepositoryFile(LAUNCHER);
        // guardHeld(...) is the fix that actually matters here: an
        // isDaemonRunning() (or any ps-based) check is asynchronous, so it
        // cannot by itself stop two overlapping calls to the SAME launch
        // method from both observing "not running" and both spawning a
        // watchdog. Only the in-process, self-healing timestamped latch
        // closes that window. Listed together (not just sentryLaunchStartedAt)
        // so a future core daemon added without one fails here too, and so a
        // change that keeps calling guardHeld() but stops actually latching
        // (e.g. drops the "= System.currentTimeMillis()" that arms it) is
        // still caught by everyLatchIsArmedAfterItsGuardCheck below.
        for (String latchCall : new String[]{
                "guardHeld(cameraLaunchStartedAt)",
                "guardHeld(sentryLaunchStartedAt)",
                "guardHeld(accSentryLaunchStartedAt)",
                "guardHeld(telegramLaunchStartedAt)"}) {
            assertTrue(latchCall + " must guard its launch method against concurrent calls",
                    source.contains(latchCall));
        }
    }

    @Test
    public void everyLatchIsArmedAfterItsGuardCheck() throws IOException {
        String source = readRepositoryFile(LAUNCHER);
        // A guardHeld() check with nothing ever setting the timestamp is a
        // guard that never actually holds. Each latch field must be assigned
        // System.currentTimeMillis() somewhere in the file (immediately after
        // its own guard check, in every core launcher today).
        for (String field : new String[]{
                "cameraLaunchStartedAt", "sentryLaunchStartedAt",
                "accSentryLaunchStartedAt", "telegramLaunchStartedAt"}) {
            assertTrue(field + " must be armed with System.currentTimeMillis() " +
                            "after its guardHeld() check, or the latch can never hold",
                    source.contains(field + " = System.currentTimeMillis()"));
        }
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
