package com.seenot.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.seenot.app.domain.SessionManager
import com.seenot.app.service.SeenotAccessibilityService
import com.seenot.app.ui.theme.SeenotTheme
import com.seenot.app.ui.screens.MainScreen

class MainActivity : ComponentActivity() {
    private var authCallbackUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authCallbackUri = extractAuthCallbackUri(intent)

        // Get intent extras
        val action = intent?.getStringExtra("action")
        val packageName = intent?.getStringExtra("package_name")

        setContent {
            SeenotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        startWithVoiceInput = action == "voice_input",
                        voiceInputPackageName = packageName,
                        authCallbackUri = authCallbackUri,
                        onAuthCallbackConsumed = { authCallbackUri = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractAuthCallbackUri(intent)?.let { authCallbackUri = it }
    }

    override fun onResume() {
        super.onResume()
        SeenotAccessibilityService.instance?.onMainActivityResumed()
        SessionManager.getInstance(applicationContext).pauseActiveMonitoringForMainActivity()
    }

    private fun extractAuthCallbackUri(intent: Intent?): Uri? {
        val data = intent?.data ?: return null
        return if (
            intent.action == Intent.ACTION_VIEW &&
            data.scheme == "seenot" &&
            data.host == "auth" &&
            data.path == "/callback"
        ) {
            data
        } else {
            null
        }
    }
}
