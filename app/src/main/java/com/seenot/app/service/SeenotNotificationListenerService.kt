package com.seenot.app.service

import android.service.notification.NotificationListenerService
import com.seenot.app.utils.Logger

class SeenotNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "SeenotNotificationListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Logger.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Logger.d(TAG, "Notification listener disconnected")
    }
}
