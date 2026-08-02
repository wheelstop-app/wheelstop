package app.wheelstop.android.telegram.impl;

import app.wheelstop.android.telegram.INotificationRouter;
import app.wheelstop.android.telegram.IOwnerStore;
import app.wheelstop.android.telegram.event.ITelegramEventBus;
import app.wheelstop.android.telegram.event.SystemEvent;
import app.wheelstop.android.telegram.event.VideoEvent;
import app.wheelstop.android.telegram.model.NotificationPreferences;
import app.wheelstop.android.telegram.model.OwnerInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Routes system events to Telegram notifications based on owner preferences.
 * Implements preference filtering and non-blocking video uploads.
 */
public class NotificationRouter implements INotificationRouter, ITelegramEventBus.EventListener {
    
    private final IOwnerStore ownerStore;
    private final TelegramSender sender;
    
    // Background executor for video uploads
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TelegramUpload");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Interface for sending Telegram messages.
     * Allows decoupling from TelegramBotDaemon.
     */
    public interface TelegramSender {
        boolean sendMessage(long chatId, String text);
        boolean sendVideo(long chatId, String videoPath, String caption);
    }
    
    public NotificationRouter(IOwnerStore ownerStore, TelegramSender sender) {
        this.ownerStore = ownerStore;
        this.sender = sender;
    }
    
    @Override
    public void onEvent(SystemEvent event) {
        OwnerInfo owner = ownerStore.getOwner();
        if (owner == null) return;  // No owner to notify
        
        NotificationPreferences prefs = ownerStore.getPreferences();
        
        switch (event.getType()) {
            case CRITICAL:
                if (prefs.isCriticalAlerts()) {
                    sendTextMessage(event.getMessage());
                }
                break;
                
            case TUNNEL:
                if (prefs.isConnectivityUpdates()) {
                    sendTextMessage(event.getMessage());
                }
                break;
                
            case MOTION:
                if (prefs.isMotionText()) {
                    sendTextMessage(event.getMessage());
                }
                break;
                
            case VIDEO:
                if (prefs.isVideoUploads()) {
                    VideoEvent ve = (VideoEvent) event;
                    // Non-blocking upload
                    uploadExecutor.execute(() -> {
                        sendVideo(ve.getFilePath(), ve.getMessage());
                    });
                }
                break;
                
            case CONNECTIVITY:
                if (prefs.isConnectivityUpdates()) {
                    sendTextMessage(event.getMessage());
                }
                break;
        }
    }
    
    @Override
    public boolean sendTextMessage(String message) {
        OwnerInfo owner = ownerStore.getOwner();
        if (owner == null) return false;
        
        return sender.sendMessage(owner.getChatId(), message);
    }
    
    @Override
    public boolean sendVideo(String videoPath, String caption) {
        OwnerInfo owner = ownerStore.getOwner();
        if (owner == null) return false;
        
        return sender.sendVideo(owner.getChatId(), videoPath, caption);
    }
}
