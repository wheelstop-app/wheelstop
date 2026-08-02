package app.wheelstop.android.telegram.event;

import app.wheelstop.android.telegram.TelegramMessages;

/**
 * Network connectivity change event.
 */
public class ConnectivityEvent extends SystemEvent {
    private final boolean connected;
    private final String networkType;  // "WiFi", "4G", "Ethernet"
    
    public ConnectivityEvent(boolean connected, String networkType) {
        super(EventType.CONNECTIVITY);
        this.connected = connected;
        this.networkType = networkType;
    }
    
    public boolean isConnected() { return connected; }
    public String getNetworkType() { return networkType; }
    
    @Override
    public String getMessage() {
        return TelegramMessages.get(connected
                        ? "legacy.connectivity.connected"
                        : "legacy.connectivity.disconnected",
                networkType);
    }
}
