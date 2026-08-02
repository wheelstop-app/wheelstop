package app.wheelstop.android.storage

import android.content.Context
import android.util.Log
import app.wheelstop.android.config.ConfigManager
import java.io.File

/**
 * One-time, first-boot migration that MOVES the legacy `Overdrive`-named
 * media roots (from the pre-rebrand app) to their `Wheelstop` counterparts,
 * so existing recordings/backups stay visible in the library after the
 * rebrand instead of being silently orphaned on disk.
 *
 * Background: [StorageManager] now enumerates ONLY the `Wheelstop/` roots
 * (see `INTERNAL_BASE_DIR` / `initVolumeDirectories`). Without this
 * migration, `RecordingsIndex.reconcile()` treats every pre-existing file
 * still sitting under the old `Overdrive/` roots as gone and purges its
 * index row on the first run after upgrade — the files stay on disk (dead
 * space) but vanish from the app's library.
 *
 * IMPORTANT ORDERING: this MUST run before [StorageManager.getInstance] is
 * touched anywhere in the process. `StorageManager`'s constructor eagerly
 * creates the `Wheelstop`-named directories (`initDirectories()` /
 * `initVolumeDirectories()`); once those exist, this migration's "the
 * Wheelstop dir doesn't exist yet" guard would always skip the rename,
 * permanently orphaning the legacy files. That's why [runIfNeeded] is
 * invoked at the very top of `WheelstopApplication.onCreate()`, before any
 * recording/index/daemon work.
 *
 * Best-effort and never fatal: every step is wrapped so a permission or
 * filesystem hiccup logs a warning instead of crashing startup. Guarded by
 * a dedicated one-shot pref, in its OWN prefs file (deliberately not one of
 * the preserved `overdrive_*` prefs names) — but the guard is only set on
 * genuine success (or when there was nothing to migrate in the first
 * place). A fresh install (or an upgrade install) can reach this code
 * before the user has granted MANAGE_EXTERNAL_STORAGE; if legacy dirs exist
 * but the app doesn't have storage access yet, or a rename attempt fails
 * for any other reason, the guard is left UNSET so the migration retries on
 * the next launch instead of permanently orphaning the recordings.
 */
object LegacyMediaMigration {
    private const val TAG = "LegacyMediaMigration"

    // Own prefs file for the one-shot guard — NOT "overdrive_config" or any
    // other preserved overdrive_* prefs name; those stay untouched as part
    // of the upstream data-compat surface.
    private const val PREFS_NAME = "wheelstop_migrations"
    private const val KEY_DONE = "legacy_overdrive_dirs_migrated_v1"

    // Mirrors StorageManager.INTERNAL_BASE_DIR ("/storage/emulated/0/Wheelstop").
    // Environment.getExternalStorageDirectory() (used by SettingsAboutFragment's
    // publicBackupDir) resolves to the same "/storage/emulated/0" root, so
    // renaming this one directory also carries the nested
    // "Overdrive/backups" folder along with it — no separate backups-root
    // handling needed.
    private const val INTERNAL_OLD = "/storage/emulated/0/Overdrive"
    private const val INTERNAL_NEW = "/storage/emulated/0/Wheelstop"

    // Mirrors ConfigManager's default outputDir ("/sdcard/DCIM/Wheelstop").
    private const val DCIM_OLD = "/storage/emulated/0/DCIM/Overdrive"
    private const val DCIM_NEW = "/storage/emulated/0/DCIM/Wheelstop"

    // Removable (SD/USB) volumes are mounted at "/storage/<uuid>" — the same
    // pattern StorageManager's own volume discovery uses (mountPath =
    // "/storage/" + uuid). We walk that directly with plain File I/O instead
    // of going through StorageManager, whose singleton constructor would
    // create the Wheelstop dirs as a side effect (see ordering note above).
    private const val VOLUME_ROOT = "/storage"

    /**
     * Run the migration if it hasn't already succeeded on this install.
     * Safe to call on every app start:
     *  - No-ops immediately once the one-shot "done" flag is set.
     *  - If no legacy Overdrive dir exists anywhere (fixed roots or any
     *    mounted removable volume), marks done immediately — nothing to
     *    migrate, no point ever checking again.
     *  - If legacy dirs exist but the app doesn't yet have external-storage
     *    access ([StorageSetup.checkStoragePermission]), defers WITHOUT
     *    setting the flag: attempting the move now would be guaranteed to
     *    fail, and burning the one-shot flag on that failure would
     *    permanently orphan the recordings once permission does land.
     *  - Otherwise attempts every pending move (plus the outputDir pref
     *    rewrite); the flag is set only if EVERY attempt succeeded. Any
     *    single failure (renameTo() returns false, or throws) leaves the
     *    flag unset so the whole thing retries on the next launch.
     */
    fun runIfNeeded(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DONE, false)) return

            val candidates = buildCandidateList()

            if (candidates.none { (old, _) -> File(old).exists() }) {
                prefs.edit().putBoolean(KEY_DONE, true).apply()
                Log.i(TAG, "No legacy Overdrive dirs found; nothing to migrate")
                return
            }

            if (!StorageSetup.checkStoragePermission(context)) {
                Log.i(TAG, "Legacy Overdrive dirs found but storage access not yet granted; " +
                        "deferring migration to a later launch")
                return
            }

            var allOk = true
            for ((old, new) in candidates) {
                if (!migrateDir(old, new)) allOk = false
            }
            if (!rewriteOutputDirPref(context)) allOk = false

            if (allOk) {
                prefs.edit().putBoolean(KEY_DONE, true).apply()
                Log.i(TAG, "Legacy Overdrive media migration complete")
            } else {
                Log.w(TAG, "Legacy Overdrive media migration incomplete (at least one move " +
                        "failed); will retry on next launch")
            }
        } catch (e: Exception) {
            // Never let a migration hiccup block app startup. Deliberately do
            // NOT set the done flag here — an unexpected exception means we
            // don't know what succeeded, so retry next launch.
            Log.w(TAG, "Legacy media migration failed (non-fatal): ${e.message}", e)
        }
    }

    /** Fixed roots plus every removable-volume root currently mounted under /storage. */
    private fun buildCandidateList(): List<Pair<String, String>> {
        val candidates = mutableListOf(
            INTERNAL_OLD to INTERNAL_NEW,
            DCIM_OLD to DCIM_NEW
        )
        try {
            val entries = File(VOLUME_ROOT).listFiles()
            if (entries != null) {
                for (entry in entries) {
                    if (!entry.isDirectory) continue
                    // "emulated" is internal storage (handled above); "self" is a
                    // symlink alias — neither is a removable volume mount point.
                    if (entry.name == "emulated" || entry.name == "self") continue
                    val oldDir = File(entry, "Overdrive")
                    val newDir = File(entry, "Wheelstop")
                    candidates.add(oldDir.absolutePath to newDir.absolutePath)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Removable-volume discovery failed: ${e.message}")
        }
        return candidates
    }

    /**
     * Best-effort same-volume rename of [oldPath] -> [newPath].
     *
     * @return true if this pair is fully resolved and needs no retry: the
     *         legacy dir doesn't exist, the destination already exists
     *         (no-clobber skip), or the rename succeeded. Returns false only
     *         when a rename was actually attempted and failed — the caller
     *         uses that to withhold the one-shot "done" flag so this pair is
     *         retried on the next launch.
     */
    private fun migrateDir(oldPath: String, newPath: String): Boolean {
        return try {
            val oldDir = File(oldPath)
            if (!oldDir.exists()) return true
            val newDir = File(newPath)
            if (newDir.exists()) {
                Log.i(TAG, "Skip migrating $oldPath -> $newPath: destination already exists")
                return true
            }
            val ok = oldDir.renameTo(newDir)
            if (ok) {
                Log.i(TAG, "Migrated legacy media dir: $oldPath -> $newPath")
            } else {
                Log.w(TAG, "renameTo() failed for $oldPath -> $newPath; will retry next launch")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "migrateDir($oldPath -> $newPath) failed: ${e.message}")
            false
        }
    }

    /**
     * If the persisted ConfigManager `outputDir` pref still points at the
     * old Overdrive path (under the internal volume), rewrite it to the
     * equivalent Wheelstop path so the config points at the migrated dir.
     *
     * @return true if no rewrite was needed, or the rewrite succeeded; false
     *         if a rewrite was needed and threw.
     */
    private fun rewriteOutputDirPref(context: Context): Boolean {
        return try {
            val configManager = ConfigManager.getInstance(context)
            val config = configManager.getAppConfig()
            val old = config.outputDir
            if (old.startsWith("/storage/emulated/0") || old.startsWith("/sdcard")) {
                val segments = old.split("/")
                if (segments.any { it == "Overdrive" }) {
                    val updated = segments.joinToString("/") { if (it == "Overdrive") "Wheelstop" else it }
                    if (updated != old) {
                        configManager.updateAppConfig(config.copy(outputDir = updated))
                        Log.i(TAG, "Rewrote persisted outputDir pref: $old -> $updated")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "rewriteOutputDirPref failed: ${e.message}")
            false
        }
    }
}
