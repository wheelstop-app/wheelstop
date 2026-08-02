package app.wheelstop.android.preflight

import app.wheelstop.android.launcher.AdbDaemonLauncher

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

    /** Stop the old app's live process. Does not survive a relaunch/reboot. */
    fun stop(launcher: AdbDaemonLauncher, callback: AdbDaemonLauncher.LaunchCallback) {
        launcher.executeShellCommand("am force-stop $OLD_PKG", callback)
    }

    /** Disable the old app for the current user. Survives reboot; user can re-enable it later. */
    fun disable(launcher: AdbDaemonLauncher, callback: AdbDaemonLauncher.LaunchCallback) {
        launcher.executeShellCommand("pm disable-user --user 0 $OLD_PKG", callback)
    }

    /** Uninstall the old app outright. */
    fun uninstall(launcher: AdbDaemonLauncher, callback: AdbDaemonLauncher.LaunchCallback) {
        launcher.executeShellCommand("pm uninstall $OLD_PKG", callback)
    }
}
