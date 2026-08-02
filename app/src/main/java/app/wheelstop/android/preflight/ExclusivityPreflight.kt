package app.wheelstop.android.preflight

import app.wheelstop.android.launcher.AdbDaemonLauncher
import app.wheelstop.android.ui.model.DaemonType

/**
 * Wheelstop (app.wheelstop.android) and the legacy Overdrive app (see
 * [OLD_PKG]) share singleton hardware — the same camera, the same tunnel
 * ports, the same MQTT client id, and the same /data/local/tmp sentinel
 * files — so only ONE of them may run its daemon stack at a time.
 *
 * This preflight probes for a live Overdrive install over the self ADB
 * connection (the same UID-2000 loopback every other daemon launcher in
 * this codebase already uses — see AdbShellExecutor / AdbDaemonLauncher)
 * and classifies whether it is safe for Wheelstop's own daemons to start.
 *
 * [OLD_PKG] is the ONE intentional reference to the legacy package name
 * left anywhere in this codebase (see Task 3's "0 residual old-package-name"
 * sweep gate) — Wheelstop needs it here, and only here, to detect and offer
 * to retire the old install.
 */
object ExclusivityPreflight {

    /** The legacy package name. The sole intentional reference — see class doc. */
    const val OLD_PKG = "com.overdrive.app"

    enum class Verdict { EXCLUSIVE, CONTENDED }

    /**
     * Pure decision, no I/O: is it safe for Wheelstop's daemons to start?
     *  - not installed         -> EXCLUSIVE (nothing to contend with)
     *  - installed, not active -> EXCLUSIVE (dormant install; no live process
     *                             is holding the shared hardware/ports/MQTT id)
     *  - installed AND active  -> CONTENDED (a live Overdrive process is
     *                             holding them right now)
     */
    fun classify(installed: Boolean, active: Boolean): Verdict =
        if (installed && active) Verdict.CONTENDED else Verdict.EXCLUSIVE

    /**
     * Probe: is [OLD_PKG] installed? `pm path` prints a
     * `package:/data/app/.../base.apk` line and exits 0 when the package is
     * installed; it exits non-zero with no output when it isn't. We treat
     * "exited 0 AND printed something" as installed, so a redirected stderr
     * or a quirky ROM that exits 0 with empty stdout still reads as "not
     * installed" rather than a false positive.
     */
    fun probeInstalled(launcher: AdbDaemonLauncher, callback: (Boolean) -> Unit) {
        var sawOutput = false
        launcher.executeShellCommand(
            "pm path $OLD_PKG 2>/dev/null",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    if (message.isNotBlank()) sawOutput = true
                }
                override fun onLaunched() { callback(sawOutput) }
                // Non-zero exit (package not found) is the expected "not
                // installed" outcome here, not a transport failure.
                override fun onError(error: String) { callback(false) }
            }
        )
    }

    /**
     * Probe: is [OLD_PKG] actively running right now? `pidof` prints a
     * non-empty pid list only while the process is alive.
     *
     * Deliberately probes just the old app's MAIN process, not individual
     * daemon processes — both apps use the same daemon process names
     * (camera_daemon, sentry_daemon, ...), so attributing a live daemon
     * process to one package or the other isn't reliable. The MAIN process
     * is the one unambiguous signal that "Overdrive itself is running."
     */
    fun probeActive(launcher: AdbDaemonLauncher, callback: (Boolean) -> Unit) {
        var sawPid = false
        launcher.executeShellCommand(
            "pidof $OLD_PKG 2>/dev/null",
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    if (message.trim().isNotEmpty()) sawPid = true
                }
                override fun onLaunched() { callback(sawPid) }
                override fun onError(error: String) { callback(false) }
            }
        )
    }

    /**
     * Run both probes over the self ADB connection and deliver the
     * classified [Verdict]. Short-circuits the active-probe when the old
     * app isn't installed — nothing to check.
     */
    fun check(launcher: AdbDaemonLauncher, callback: (Verdict) -> Unit) {
        probeInstalled(launcher) { installed ->
            if (!installed) {
                callback(Verdict.EXCLUSIVE)
                return@probeInstalled
            }
            probeActive(launcher) { active ->
                callback(classify(installed, active))
            }
        }
    }

    /**
     * The core daemons (camera / sentry / acc-sentry) this codebase launches
     * DETACHED under UID 2000 (`nohup sh -c '...' &` over the ADB self-
     * connection — see DaemonLauncher's launch* methods) specifically so
     * they survive an app kill. That means `am force-stop` / `pm
     * disable-user` / `pm uninstall` against [OLD_PKG] — which only ever
     * touch the OLD APP'S OWN UID — do NOT stop them. Every action below
     * therefore runs its am/pm command and THEN sweeps this daemon family:
     * plants each one's `.disabled` sentinel (so a live watchdog script
     * exits instead of respawning it) and kills any live process by name.
     *
     * Safe to run unconditionally: every caller of stop/disable/uninstall
     * only runs while the exclusivity gate reads CONTENDED, and Wheelstop's
     * OWN daemons are held off for the entire duration of CONTENDED (see
     * DaemonStartupManager's initializeOnAppLaunch/initializeOnBoot gate) —
     * so any camera_daemon/sentry_daemon/acc_sentry_daemon process alive
     * right now is unambiguously the OLD app's, never Wheelstop's own.
     */
    private val SWEPT_CORE_DAEMONS: List<DaemonType> = listOf(
        DaemonType.CAMERA_DAEMON,
        DaemonType.SENTRY_DAEMON,
        DaemonType.ACC_SENTRY_DAEMON
    )

    /** Stop the old app's own process AND sweep its detached UID-2000 daemon family. */
    fun stop(launcher: AdbDaemonLauncher, callback: AdbDaemonLauncher.LaunchCallback) {
        runActionWithDaemonSweep(launcher, "am force-stop $OLD_PKG", callback)
    }

    /**
     * Disable the old app for the current user (survives reboot; user can
     * re-enable it later) AND sweep its detached UID-2000 daemon family —
     * disabling the package does not stop already-running UID-2000
     * processes.
     */
    fun disable(launcher: AdbDaemonLauncher, callback: AdbDaemonLauncher.LaunchCallback) {
        runActionWithDaemonSweep(launcher, "pm disable-user --user 0 $OLD_PKG", callback)
    }

    /**
     * Uninstall the old app outright AND sweep its detached UID-2000 daemon
     * family — uninstalling the APK does not touch already-running
     * UID-2000 processes, since they aren't part of the app's own UID.
     */
    fun uninstall(launcher: AdbDaemonLauncher, callback: AdbDaemonLauncher.LaunchCallback) {
        runActionWithDaemonSweep(launcher, "pm uninstall $OLD_PKG", callback)
    }

    /**
     * Run [pkgCommand] (the am/pm action against the old app's own UID),
     * then sweep its UID-2000 daemon family regardless of whether
     * [pkgCommand] itself succeeded — a "the app was already stopped /
     * disabled / uninstalled" failure from [pkgCommand] doesn't mean the
     * daemon family is down too, so bailing out on that error would leave
     * exactly the contention this whole action exists to clear.
     */
    private fun runActionWithDaemonSweep(
        launcher: AdbDaemonLauncher,
        pkgCommand: String,
        callback: AdbDaemonLauncher.LaunchCallback
    ) {
        launcher.executeShellCommand(pkgCommand, object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) { callback.onLog(message) }
            override fun onLaunched() { sweepOldDaemons(launcher, callback) }
            override fun onError(error: String) {
                callback.onLog("'$pkgCommand' failed ($error) — sweeping daemons anyway")
                sweepOldDaemons(launcher, callback)
            }
        })
    }

    private fun sweepOldDaemons(launcher: AdbDaemonLauncher, callback: AdbDaemonLauncher.LaunchCallback) {
        launcher.executeShellCommand(sweepScript(), callback)
    }

    /**
     * Plants each [SWEPT_CORE_DAEMONS] sentinel, then kills any live
     * process by name. Uses the ps + awk + kill idiom from
     * AdbShellExecutor.killProcess — NOT `pkill -f` — because the ADB shell
     * service runs this whole string as `sh -c "<cmd>"`, so the invoking
     * shell's own argv literally contains each process-name pattern;
     * `pkill -f` would match and SIGKILL that shell itself before later
     * commands in the same payload run (see AdbShellExecutor.executeScript's
     * doc comment for the full self-match footgun). Capturing `MY_PID=$$`
     * once up front and excluding it from every kill loop — exactly what
     * AdbShellExecutor.killProcess already does for a single process name —
     * avoids that self-match here across all three.
     */
    private fun sweepScript(): String {
        val sentinelPlants = SWEPT_CORE_DAEMONS.joinToString(" ") { type ->
            "echo 1 > ${type.sentinelPath} 2>/dev/null; chmod 666 ${type.sentinelPath} 2>/dev/null;"
        }
        val kills = SWEPT_CORE_DAEMONS.joinToString(" ") { type ->
            "ps -A -o PID,ARGS | grep -F '${type.processName}' | grep -v grep | awk '{print \$1}' " +
                "| while read pid; do if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -9 \$pid 2>/dev/null; fi; done;"
        }
        return "$sentinelPlants MY_PID=\$\$; $kills echo swept"
    }
}
