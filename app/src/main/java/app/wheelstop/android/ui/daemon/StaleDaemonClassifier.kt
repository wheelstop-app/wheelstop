package app.wheelstop.android.ui.daemon

/** Whether a running daemon is executing the installed APK. */
enum class DaemonApkState {
    /** Loaded APK path equals the installed one. */
    CURRENT,

    /** Loaded APK path is present and different — act on this. */
    STALE,

    /**
     * The loaded path could not be established. NEVER treated as stale:
     * an unreadable proc entry would otherwise trigger a
     * surveillance-interrupting restart loop on a parked car.
     */
    UNKNOWN
}

/**
 * Decides whether a daemon is running the installed APK, from the `CLASSPATH`
 * recorded in its `/proc/<pid>/environ`.
 *
 * Each daemon is launched as `CLASSPATH=<framework jars>:<apk> app_process …`,
 * so the APK it actually loaded is fixed in its environment for the life of the
 * process. Android assigns a NEW random path suffix on every install — including
 * a same-version reinstall — so comparing paths catches every reinstall, which a
 * version comparison would not.
 *
 * Pure and Android-free so the unknown-is-not-stale rule can be asserted directly.
 */
object StaleDaemonClassifier {

    private const val DATA_APP_PREFIX = "/data/app/"

    /**
     * The `/data/app/…` entry of the process's CLASSPATH, or null when the
     * environment carries no such entry (framework jars only, no CLASSPATH at
     * all, or an unreadable read).
     *
     * Accepts NUL- or newline-separated text: the raw file is NUL-separated, but
     * the device read pipes it through `tr` to survive the shell channel.
     */
    @JvmStatic
    fun apkPathFromEnviron(environ: String): String? {
        val entry = environ.split(' ', '\n')
            .firstOrNull { it.startsWith("CLASSPATH=") }
            ?: return null
        return entry.removePrefix("CLASSPATH=")
            .split(':')
            .firstOrNull { it.startsWith(DATA_APP_PREFIX) }
    }

    /**
     * @param environ           raw environment text, or null if the read failed
     * @param expectedApkPath   `context.applicationInfo.sourceDir`
     */
    @JvmStatic
    fun classify(environ: String?, expectedApkPath: String): DaemonApkState {
        if (environ.isNullOrEmpty()) return DaemonApkState.UNKNOWN
        // A blank expected path means we could not read our OWN install location.
        // Comparing against it would mark every daemon stale and reset the world.
        if (expectedApkPath.isBlank()) return DaemonApkState.UNKNOWN
        val running = apkPathFromEnviron(environ) ?: return DaemonApkState.UNKNOWN
        return if (running == expectedApkPath) DaemonApkState.CURRENT else DaemonApkState.STALE
    }
}
