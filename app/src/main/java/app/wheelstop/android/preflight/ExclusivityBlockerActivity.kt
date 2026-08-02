package app.wheelstop.android.preflight

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.wheelstop.android.launcher.AdbDaemonLauncher
import app.wheelstop.android.logging.LogManager
import app.wheelstop.android.ui.daemon.DaemonStartupManager

/**
 * Full-screen blocking gate shown when [ExclusivityPreflight] reports
 * CONTENDED — a live legacy Overdrive install is holding the shared camera /
 * tunnel ports / MQTT client id / sentinel files Wheelstop's own daemons
 * need.
 *
 * Modeled on [app.wheelstop.android.BlockerActivity]'s "block everything,
 * no way around it" pattern (programmatic views, no XML layout, back/keys
 * swallowed, keep-screen-on) but with visible content instead of a black
 * shield: the user needs to read the reason and pick one of three actions.
 *
 * Re-probes after every action and again on every onResume; only finishes
 * (and hands off to [DaemonStartupManager] to actually start Wheelstop's
 * daemons) once the verdict flips to EXCLUSIVE.
 */
class ExclusivityBlockerActivity : Activity() {

    private val log = LogManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adbLauncher: AdbDaemonLauncher
    private lateinit var statusText: TextView
    private lateinit var actionButtons: List<Button>

    companion object {
        private const val TAG = "ExclusivityBlocker"

        /**
         * Launch the blocker over whatever is currently on screen. [context]
         * may be an Activity or an application Context (the boot path only
         * has the latter), so FLAG_ACTIVITY_NEW_TASK is always required.
         */
        fun start(context: Context) {
            val intent = Intent(context, ExclusivityBlockerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.applicationContext.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adbLauncher = AdbDaemonLauncher(applicationContext)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(buildContentView())
        recheck()
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(72, 72, 72, 72)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val title = TextView(this).apply {
            text = "Wheelstop can't start its daemons"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = reasonText()
            setTextColor(Color.LTGRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 48)
        }
        root.addView(statusText)

        val stopButton = Button(this).apply {
            text = "Stop Overdrive"
            setOnClickListener {
                runAction("Stopping Overdrive...") { cb -> ExclusivityPreflight.stop(adbLauncher, cb) }
            }
        }
        val disableButton = Button(this).apply {
            text = "Disable Overdrive"
            setOnClickListener {
                runAction("Disabling Overdrive...") { cb -> ExclusivityPreflight.disable(adbLauncher, cb) }
            }
        }
        val uninstallButton = Button(this).apply {
            text = "Uninstall Overdrive"
            setOnClickListener {
                runAction("Uninstalling Overdrive...") { cb -> ExclusivityPreflight.uninstall(adbLauncher, cb) }
            }
        }
        actionButtons = listOf(stopButton, disableButton, uninstallButton)
        actionButtons.forEach { button ->
            root.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 })
        }

        return root
    }

    private fun reasonText(): String =
        "The old Overdrive app (${ExclusivityPreflight.OLD_PKG}) is installed and " +
            "running. It shares the camera, tunnel ports, MQTT client id, and " +
            "on-disk sentinels with Wheelstop, and only one of them can run its " +
            "daemons at a time.\n\nStop, disable, or uninstall the old app to continue."

    private fun setActionsEnabled(enabled: Boolean) {
        actionButtons.forEach { it.isEnabled = enabled }
    }

    private fun runAction(progressMessage: String, action: (AdbDaemonLauncher.LaunchCallback) -> Unit) {
        setActionsEnabled(false)
        statusText.text = progressMessage
        action(object : AdbDaemonLauncher.LaunchCallback {
            override fun onLog(message: String) {}
            override fun onLaunched() { handler.post { onActionDone(true, null) } }
            override fun onError(error: String) { handler.post { onActionDone(false, error) } }
        })
    }

    private fun onActionDone(success: Boolean, error: String?) {
        log.info(TAG, "Exclusivity action completed success=$success error=$error")
        if (!success) {
            Toast.makeText(this, "Action failed: $error", Toast.LENGTH_LONG).show()
        }
        recheck()
    }

    /** Re-probe over the self ADB connection and update the screen (or unblock) accordingly. */
    private fun recheck() {
        ExclusivityPreflight.check(adbLauncher) { verdict ->
            handler.post {
                when (verdict) {
                    ExclusivityPreflight.Verdict.EXCLUSIVE -> onExclusive()
                    ExclusivityPreflight.Verdict.CONTENDED -> {
                        setActionsEnabled(true)
                        statusText.text = reasonText()
                    }
                }
            }
        }
    }

    private fun onExclusive() {
        log.info(TAG, "Exclusivity preflight resolved to EXCLUSIVE — starting Wheelstop daemons")
        // Hand off to the normal daemon-startup path now that the gate is clear.
        // DaemonStartupManager.initializeOnAppLaunch() re-runs the same preflight
        // gate first (finds EXCLUSIVE immediately this time) before proceeding,
        // so this is safe even if the verdict were to flip again in the interim.
        DaemonStartupManager(applicationContext).initializeOnAppLaunch()
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Catches the case where the old app was stopped/disabled/uninstalled
        // from somewhere other than this screen's buttons (e.g. a manual adb
        // session) while we were backgrounded.
        recheck()
    }

    override fun onBackPressed() {
        // Block back — the whole point is that this cannot be dismissed
        // without resolving the contention.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = true

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = true
}
