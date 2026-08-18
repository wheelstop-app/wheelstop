package app.wheelstop.android.ui.daemon

import app.wheelstop.android.launcher.AdbDaemonLauncher
import app.wheelstop.android.launcher.AdbShellExecutor
import app.wheelstop.android.logging.LogManager

/** A core daemon observed running an APK other than the installed one. */
data class StaleDaemon(
    val name: String,
    val pid: Int,
    val runningApkPath: String?
)

/**
 * Finds core daemons still executing a previous APK.
 *
 * Staleness is OBSERVED, not inferred. The in-app update path infers it from a
 * broadcast plus two sentinel files, and a sideload sends none of those: BYD
 * suppresses `ACTION_MY_PACKAGE_REPLACED` on this ROM, `AppUpdater` never runs,
 * and a hand-launched app carries no post-update intent extra. Reading the
 * CLASSPATH out of `/proc/<pid>/environ` needs no cooperation from any of them.
 *
 * Takes ONE process-table snapshot and ONE batched environ read for all pids.
 * Per-daemon probing was deliberately removed from the health check: each probe
 * cost two adb shell sessions plus a full `/proc` walk, serialised on a
 * process-wide lock, taken from a shared SYSTEM adbd every 30s forever.
 */
class StaleDaemonDetector(
    private val adbLauncher: AdbDaemonLauncher,
    private val shell: AdbShellExecutor,
    private val log: LogManager
) {
    companion object {
        private const val TAG = "StaleDaemonDetector"

        /** Core daemons only — optional daemons carry durable user-stop semantics. */
        val CORE_PROCESS_NAMES = listOf(
            "byd_cam_daemon",
            "sentry_daemon",
            "acc_sentry_daemon"
        )

        /** Marks each pid's block so one read can be split back per process. */
        internal const val PID_MARKER = "__WS_PID__"

        /**
         * One shell invocation for every pid. `tr` converts the NUL separators to
         * newlines so the output survives the line-oriented shell channel.
         */
        internal fun buildEnvironCommand(pids: List<Int>): String =
            pids.joinToString("; ") { pid ->
                "echo $PID_MARKER$pid; cat /proc/$pid/environ 2>/dev/null | tr '\\0' '\\n'"
            }

        /** Split a [buildEnvironCommand] result back into pid → environ text. */
        internal fun parseEnvironOutput(output: String): Map<Int, String> {
            val result = LinkedHashMap<Int, String>()
            var currentPid: Int? = null
            val buffer = StringBuilder()
            fun flush() {
                currentPid?.let { result[it] = buffer.toString() }
                buffer.setLength(0)
            }
            for (line in output.lineSequence()) {
                if (line.startsWith(PID_MARKER)) {
                    flush()
                    currentPid = line.removePrefix(PID_MARKER).trim().toIntOrNull()
                } else if (currentPid != null) {
                    buffer.append(line).append('\n')
                }
            }
            flush()
            return result
        }
    }

    fun findStaleDaemons(expectedApkPath: String, callback: (List<StaleDaemon>) -> Unit) {
        try {
            adbLauncher.snapshotProcessTable { snapshot ->
                if (snapshot == null) {
                    log.warn(TAG, "ps snapshot unavailable — skipping stale check this pass")
                    callback(emptyList())
                    return@snapshotProcessTable
                }
                val pidsByName = LinkedHashMap<Int, String>()
                for (name in CORE_PROCESS_NAMES) {
                    for (pid in adbLauncher.pidsFor(snapshot, name)) pidsByName[pid] = name
                }
                if (pidsByName.isEmpty()) {
                    callback(emptyList())
                    return@snapshotProcessTable
                }
                shell.execute(
                    command = buildEnvironCommand(pidsByName.keys.toList()),
                    callback = object : AdbShellExecutor.ShellCallback {
                        override fun onSuccess(output: String) {
                            val environs = parseEnvironOutput(output)
                            val stale = ArrayList<StaleDaemon>()
                            for ((pid, name) in pidsByName) {
                                val environ = environs[pid]
                                when (StaleDaemonClassifier.classify(environ, expectedApkPath)) {
                                    DaemonApkState.STALE -> {
                                        val running =
                                            environ?.let { StaleDaemonClassifier.apkPathFromEnviron(it) }
                                        log.info(TAG,
                                            "STALE $name pid=$pid running=$running installed=$expectedApkPath")
                                        stale.add(StaleDaemon(name, pid, running))
                                    }
                                    DaemonApkState.UNKNOWN ->
                                        log.warn(TAG,
                                            "UNKNOWN $name pid=$pid — environ unreadable or has no " +
                                                "/data/app entry; treating as current")
                                    DaemonApkState.CURRENT -> Unit
                                }
                            }
                            callback(stale)
                        }

                        override fun onError(error: String) {
                            log.warn(TAG, "environ read failed: $error — treating all as current")
                            callback(emptyList())
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            // Never block daemon startup on the detector.
            log.error(TAG, "stale-daemon detection failed: ${t.message}")
            callback(emptyList())
        }
    }
}
