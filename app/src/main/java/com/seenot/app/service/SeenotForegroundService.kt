package com.seenot.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class SeenotForegroundService : Service() {
    companion object {
        private const val TAG = "SeenotForeground"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Foreground service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Foreground service stopped")
    }
}
