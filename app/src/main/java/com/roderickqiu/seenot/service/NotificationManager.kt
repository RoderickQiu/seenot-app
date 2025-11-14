package com.roderickqiu.seenot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.roderickqiu.seenot.MainActivity
import com.roderickqiu.seenot.R

class NotificationManager(private val context: Context) {
    private val mHandler = Handler(Looper.getMainLooper())

    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mHandler.post { Toast.makeText(context, message, duration).show() }
    }

    fun startInForeground(service: android.accessibilityservice.AccessibilityService) {
        val channelId = CHANNEL_ID
        val channelName = context.getString(R.string.fg_channel_name)
        val channelDescription = context.getString(R.string.fg_channel_desc)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(channelId, channelName, importance)
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
        channel.setBypassDnd(true)
        channel.description = channelDescription
        channel.setShowBadge(false)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingFlags)

        val notification: Notification =
                NotificationCompat.Builder(context, CHANNEL_ID)
                        .setContentTitle(context.getString(R.string.fg_notification_title))
                        .setContentText(context.getString(R.string.fg_notification_text))
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentIntent(pendingIntent)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
        service.startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "seenot_accessibility"
        private const val NOTIFICATION_ID = 1001
    }
}

