package app.wheelstop.android

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.wheelstop.android.config.ConfigChangeListener
import app.wheelstop.android.config.ConfigManager
import app.wheelstop.android.logging.LogCleaner
import app.wheelstop.android.logging.LogConfig
import app.wheelstop.android.logging.LogManager
import app.wheelstop.android.server.LocaleManager
import app.wheelstop.android.services.DaemonKeepaliveService
// import app.wheelstop.android.shell.PrivilegedShellSetup
import app.wheelstop.android.ui.util.PreferencesManager

/**
 * Application class for Overdrive.
 * Initializes global singletons before any Activity is created.
 */
class WheelstopApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()

        try {
            startService(Intent(this, app.wheelstop.android.remote.RemoteDevViewBridgeService::class.java))
        } catch (error: Throwable) {
            Log.w("WheelstopApplication", "Remote dev-view bridge unavailable: ${error.message}")
        }

        // Apply the user-picked locale before any Activity/Fragment is created.
        // Auto-mode (or unset) writes an empty list so AppCompat falls back to
        // Locale.getDefault() — i.e. the BYD head unit's system language.
        applyPersistedLocale()

        // Initialize LogConfig with app's cache directory for file logging
        LogConfig.init(this)

        // Initialize LogManager seeded from the PERSISTED logging config, and
        // wire live updates + the cleanup worker. Previously LogManager was
        // built with LogConfig.default() and the user's size/retention values
        // never reached it (dead config), and LogCleaner was never scheduled
        // (dead retention). setupLogging() closes both gaps.
        setupLogging()

        // Initialize PreferencesManager before any ViewModel is created
        PreferencesManager.init(this)

        // Apply persisted theme mode (Auto / Light / Dark) before any Activity
        // is created so the first paint matches the user's choice.
        AppCompatDelegate.setDefaultNightMode(PreferencesManager.getThemeMode())
        
        // Privileged shell (UID 1000) DISABLED — causes BYD default dashcam
        // to show "no signal" by elevating app's camera priority via accmodemanager.
        // All daemons now run via ADB shell (UID 2000) which is sufficient.
        // PrivilegedShellSetup.init(this)
        // PrivilegedShellSetup.setup(...)

        // Start DaemonKeepaliveService - handles:
        // - Foreground service with START_STICKY
        // - PARTIAL_WAKE_LOCK to prevent CPU sleep
        // - SCREEN_OFF receiver registration
        // - Daemon startup
        DaemonKeepaliveService.start(this)

        // App-process listener that binds Telenav's OEM AIDL for the daemon's
        // HTTP endpoint (the daemon can't bindService itself). Idempotent.
        app.wheelstop.android.telenav.TelenavIpcServer.start(this)
    }

    /**
     * Build the live LogConfig by merging the user's persisted logging policy
     * (size cap / retention / cleanup interval / rotation count) onto the
     * app-context file-logging settings (log dir + enable flags), seed the
     * LogManager singleton with it, push later changes live, and schedule the
     * periodic cleanup worker. Without this the persisted config never reached
     * any rotation/retention mechanism.
     */
    private fun setupLogging() {
        val base = LogConfig.default()  // carries the resolved app log dir + enable flags
        val persisted = ConfigManager.getInstance(this).getLoggingConfig()

        // Every user-owned field must be listed here. base is LogConfig.default(), so
        // anything omitted silently reverts to the compiled-in default and the persisted
        // value is inert — which is what this merge exists to prevent.
        fun merged(policy: LogConfig): LogConfig = base.copy(
            retentionHours = policy.retentionHours,
            cleanupIntervalHours = policy.cleanupIntervalHours,
            maxFileSizeMB = policy.maxFileSizeMB,
            rotationCount = policy.rotationCount,
            minLevel = policy.minLevel
        )

        // Seed the singleton with the persisted policy.
        LogManager.getInstance(merged(persisted))

        // Push live changes into the running LogManager and re-schedule the
        // cleaner so a settings change takes effect without an app restart.
        ConfigManager.getInstance(this).addConfigChangeListener(object : ConfigChangeListener {
            override fun onConfigChanged(key: String, oldValue: Any?, newValue: Any?) {
                if (key == "loggingConfig" && newValue is LogConfig) {
                    val live = merged(newValue)
                    LogManager.getInstance().updateConfig(live)
                    try {
                        LogCleaner.schedule(this@WheelstopApplication, live.cleanupIntervalHours.toLong())
                    } catch (e: Exception) {
                        Log.w("WheelstopApplication", "LogCleaner re-schedule failed: ${e.message}")
                    }
                }
            }
        })

        // Schedule the periodic app-log cleanup worker (was never enqueued, so
        // retentionHours was inert). WorkManager enforces a 15-min floor; our
        // 4h cadence is well above it.
        try {
            LogCleaner.schedule(this, persisted.cleanupIntervalHours.toLong())
        } catch (e: Exception) {
            Log.w("WheelstopApplication", "LogCleaner schedule failed: ${e.message}")
        }
    }

    private fun applyPersistedLocale() {
        try {
            val raw = LocaleManager.getRaw()
            val locales = if (raw == null || raw == LocaleManager.AUTO_TAG) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(raw)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (e: Exception) {
            Log.w("WheelstopApplication", "applyPersistedLocale failed: ${e.message}")
        }
    }
}
