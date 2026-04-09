package com.seenot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.seenot.app.ui.theme.SeenotTheme
import com.seenot.app.ui.screens.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        voiceInputPackageName = packageName
                    )
                }
            }
        }
    }
}
