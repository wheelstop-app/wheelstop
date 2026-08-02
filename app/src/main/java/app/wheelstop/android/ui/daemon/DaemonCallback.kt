package app.wheelstop.android.ui.daemon

import app.wheelstop.android.ui.model.DaemonStatus

/**
 * Callback interface for daemon operations.
 */
interface DaemonCallback {
    /**
     * Called when daemon status changes.
     */
    fun onStatusChanged(status: DaemonStatus, message: String)
    
    /**
     * Called when an error occurs.
     */
    fun onError(error: String)
}
