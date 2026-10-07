package com.ultron.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

/**
 * Native Notification Listener Service for Ultron.
 * Allows clearing all system notifications across all apps with zero cloud services.
 */
public class UltronNotificationService extends NotificationListenerService {

    private static final String TAG = "UltronNotifService";
    private static UltronNotificationService instance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Log.i(TAG, "Ultron Notification Listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (instance == this) {
            instance = null;
        }
        Log.i(TAG, "Ultron Notification Listener disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Can be used for assistant awareness if needed
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // No-op
    }

    /**
     * Clear all notifications across all apps on the device.
     */
    public static boolean clearAll() {
        if (instance != null) {
            try {
                instance.cancelAllNotifications();
                Log.i(TAG, "Successfully cleared all system notifications");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to cancel notifications: " + e.getMessage());
            }
        }
        return false;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static boolean isPermissionGranted(Context context) {
        try {
            String pkgName = context.getPackageName();
            String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
            if (!TextUtils.isEmpty(flat)) {
                String[] names = flat.split(":");
                for (String name : names) {
                    ComponentName cn = ComponentName.unflattenFromString(name);
                    if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
