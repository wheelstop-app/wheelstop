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
 * the preserved `overdrive_*` prefs names), so this only ever runs once.
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
     * Run the migration if it hasn't already run on this install. Safe to
     * call on every app start — no-ops immediately once the one-shot flag
     * is set.
     */
    fun runIfNeeded(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DONE, false)) return

            migrateDir(INTERNAL_OLD, INTERNAL_NEW)
            migrateDir(DCIM_OLD, DCIM_NEW)
            migrateRemovableVolumes()
            rewriteOutputDirPref(context)

            prefs.edit().putBoolean(KEY_DONE, true).apply()
            Log.i(TAG, "Legacy Overdrive media migration finished")
        } catch (e: Exception) {
            // Never let a migration hiccup block app startup.
            Log.w(TAG, "Legacy media migration failed (non-fatal): ${e.message}", e)
        }
    }

    /**
     * Best-effort same-volume rename of [oldPath] -> [newPath]. No-ops if
     * the legacy dir doesn't exist, and never clobbers an existing
     * destination.
     */
    private fun migrateDir(oldPath: String, newPath: String) {
        try {
            val oldDir = File(oldPath)
            if (!oldDir.exists()) return
            val newDir = File(newPath)
            if (newDir.exists()) {
                Log.i(TAG, "Skip migrating $oldPath -> $newPath: destination already exists")
                return
            }
            val ok = oldDir.renameTo(newDir)
            if (ok) {
                Log.i(TAG, "Migrated legacy media dir: $oldPath -> $newPath")
            } else {
                Log.w(TAG, "renameTo() failed for $oldPath -> $newPath; left in place")
            }
        } catch (e: Exception) {
            Log.w(TAG, "migrateDir($oldPath -> $newPath) failed: ${e.message}")
        }
    }

    private fun migrateRemovableVolumes() {
        try {
            val entries = File(VOLUME_ROOT).listFiles() ?: return
            for (entry in entries) {
                if (!entry.isDirectory) continue
                // "emulated" is internal storage (handled above); "self" is a
                // symlink alias — neither is a removable volume mount point.
                if (entry.name == "emulated" || entry.name == "self") continue
                val oldDir = File(entry, "Overdrive")
                val newDir = File(entry, "Wheelstop")
                migrateDir(oldDir.absolutePath, newDir.absolutePath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "migrateRemovableVolumes failed: ${e.message}")
        }
    }

    /**
     * If the persisted ConfigManager `outputDir` pref still points at the
     * old Overdrive path (under the internal volume), rewrite it to the
     * equivalent Wheelstop path so the config points at the migrated dir.
     */
    private fun rewriteOutputDirPref(context: Context) {
        try {
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
        } catch (e: Exception) {
            Log.w(TAG, "rewriteOutputDirPref failed: ${e.message}")
        }
    }
}
