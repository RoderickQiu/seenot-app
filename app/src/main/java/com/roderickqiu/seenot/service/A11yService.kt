package com.roderickqiu.seenot.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ContentPartBuilder
import com.aallam.openai.api.exception.OpenAIException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import io.ktor.client.call.NoTransformationFoundException
import com.roderickqiu.seenot.MainActivity
import com.roderickqiu.seenot.R
import com.roderickqiu.seenot.data.AppDataStore
import com.roderickqiu.seenot.data.ConditionType
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.data.RuleCondition
import com.roderickqiu.seenot.utils.AccessibilityEventUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

class A11yService : AccessibilityService() {

    private lateinit var appDataStore: AppDataStore
    private lateinit var mHandler: Handler
    private var lastTimeClassName: String? = null
    private var lastTimeClassCapable: Boolean = false
    private var lastTimeCapableClass: String? = null
    private var foregroundWindowId: Int = -1
    private var currentMonitoredPackage: String? = null
    private var currentMonitoredAppName: String? = null
    private var lastMonitoredLogTimeMs: Long = 0L
    private var isTakingScreenshot: Boolean = false
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("A11yService", "Accessibility service connected")
        mHandler = Handler(Looper.getMainLooper())
        appDataStore = AppDataStore(this)
        startInForeground()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("A11yService", "Accessibility service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { e ->
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    foregroundWindowId = e.windowId

                    val className = e.className?.toString()
                    val capable =
                            AccessibilityEventUtils.isCapableClass(
                                    className,
                                    lastTimeClassName,
                                    lastTimeClassCapable
                            )
                    lastTimeClassName = className
                    lastTimeClassCapable = capable
                    if (capable && className != null) {
                        lastTimeCapableClass = className
                    }

                    val packageName = e.packageName?.toString()
                    if (packageName != null && capable) {
                        checkAndLogMonitoredApp(packageName)
                    }

                    // Regardless of capability, if we are currently in a monitored app,
                    // check for things once a time
                    val now = System.currentTimeMillis()
                    if (currentMonitoredPackage != null) {
                        Log.d(
                                "A11yService",
                                "Accessibility event: ${AccessibilityEventUtils.eventTypeName(e.eventType)}, className: ${e.className?.toString()}, packageName: ${e.packageName?.toString()}, text: ${e.text.toString()}"
                        )
                        if (now - lastMonitoredLogTimeMs >= LOG_INTERVAL_MS) {
                            Log.d(
                                    "A11yService",
                                    "Active in monitored app: $currentMonitoredAppName (package: $currentMonitoredPackage) via event: ${AccessibilityEventUtils.eventTypeName(e.eventType)}"
                            )
                            tryTakeScreenshot("periodic-active")
                            lastMonitoredLogTimeMs = now
                        }
                    }
                }
            }
        }
    }

    private fun getRightWindowNode(): AccessibilityNodeInfo? {
        // Compatible with multi-window mode
        val ws = windows
        for (i in 0 until ws.size) {
            val w = ws[i]
            if (w.id == foregroundWindowId) {
                return w.root
            }
        }
        return rootInActiveWindow
    }

    private fun checkAndLogMonitoredApp(packageName: String) {
        try {
            // Get app name from package name
            val packageManager = packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appName = packageManager.getApplicationLabel(appInfo).toString()

            // Load monitoring apps from settings
            val monitoringApps = appDataStore.loadMonitoringApps()

            // Check if the current app is in the monitoring list
            val isMonitored = monitoringApps.any { it.name == appName && it.isEnabled }

            // If we were in a monitored app and now switching to a different package, log exit
            // first
            val previousPackage = currentMonitoredPackage
            val previousAppName = currentMonitoredAppName
            if (previousPackage != null && previousPackage != packageName) {
                Log.d(
                        "A11yService",
                        "Exited monitored app: $previousAppName (package: $previousPackage)"
                )
                currentMonitoredPackage = null
                currentMonitoredAppName = null
                lastTimeCapableClass = null
            }

            // Then, if the new package is monitored
            if (isMonitored) {
                val now = System.currentTimeMillis()
                if (currentMonitoredPackage == null || currentMonitoredPackage != packageName) {
                    // entering a monitored app (first time or switched)
                    Log.d("A11yService", "Entered monitored app: $appName (package: $packageName)")
                    currentMonitoredPackage = packageName
                    currentMonitoredAppName = appName
                    lastMonitoredLogTimeMs = now
                    showToast(getString(R.string.monitor_app_entered) + appName)
                    tryTakeScreenshot("entered")
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // App not found, ignore
        } catch (e: Exception) {
            Log.e("A11yService", "Error checking monitored app", e)
        }
    }

    override fun onInterrupt() {
        Log.d("A11yService", "Accessibility service interrupted")
    }

    private fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        mHandler.post { Toast.makeText(applicationContext, message, duration).show() }
    }

    private fun startInForeground() {
        val channelId = CHANNEL_ID
        val channelName = getString(R.string.fg_channel_name)
        val channelDescription = getString(R.string.fg_channel_desc)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(channelId, channelName, importance)
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
        channel.setBypassDnd(true)
        channel.description = channelDescription
        channel.setShowBadge(false)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags)

        val notification: Notification =
                NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.fg_notification_title))
                        .setContentText(getString(R.string.fg_notification_text))
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentIntent(pendingIntent)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build()
        startForeground(NOTIFICATION_ID, notification)

        Log.d("A11yService", "Foreground service started")
    }

    private fun tryTakeScreenshot(reason: String) {
        if (isTakingScreenshot) {
            return
        }
        isTakingScreenshot = true
        val executor = ContextCompat.getMainExecutor(this)
        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(
                                screenshotResult: AccessibilityService.ScreenshotResult
                        ) {
                            isTakingScreenshot = false
                            Log.d(
                                    "A11yService",
                                    "Screenshot success for $currentMonitoredAppName ($currentMonitoredPackage), reason=$reason"
                            )

                            try {
                                val hb = screenshotResult.hardwareBuffer
                                val cs = screenshotResult.colorSpace
                                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hb, cs)
                                if (hardwareBitmap != null) {
                                    val originalWidth = hardwareBitmap.width
                                    val originalHeight = hardwareBitmap.height

                                    // Create a mutable copy for processing
                                    val mutableBitmap =
                                            hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)

                                    // Save original screenshot to gallery if enabled
                                    if (loadAutoSaveScreenshot()) {
                                        // Create a separate copy for saving to gallery to avoid interference
                                        val galleryBitmap = mutableBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                        coroutineScope.launch {
                                            try {
                                                saveBitmapToGallery(galleryBitmap, reason)
                                                galleryBitmap.recycle()
                                            } catch (e: Exception) {
                                                Log.e("A11yService", "Error saving screenshot to gallery", e)
                                                galleryBitmap.recycle()
                                            }
                                        }
                                    }

                                    // Scale image: max 960px on longer side, maintain aspect ratio
                                    val maxSize = 960
                                    val scale = if (originalWidth > originalHeight) {
                                        maxSize.toFloat() / originalWidth
                                    } else {
                                        maxSize.toFloat() / originalHeight
                                    }
                                    val scaledWidth = (originalWidth * scale).toInt()
                                    val scaledHeight = (originalHeight * scale).toInt()

                                    val scaledBitmap =
                                            Bitmap.createScaledBitmap(
                                                    mutableBitmap,
                                                    scaledWidth,
                                                    scaledHeight,
                                                    true
                                            )

                                    Log.d(
                                            "A11yService",
                                            "Bitmap created and scaled from screenshot: ${scaledBitmap.width}x${scaledBitmap.height} (original: ${originalWidth}x${originalHeight})"
                                    )

                                    // Describe image using AI
                                    describeImageWithAI(scaledBitmap)
                                } else {
                                    Log.w(
                                            "A11yService",
                                            "Failed to wrap hardware buffer into Bitmap"
                                    )
                                }
                            } catch (t: Throwable) {
                                Log.e("A11yService", "Error creating Bitmap from screenshot", t)
                            } finally {
                                try {
                                    screenshotResult.hardwareBuffer.close()
                                } catch (_: Throwable) {}
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            isTakingScreenshot = false
                            Log.w(
                                    "A11yService",
                                    "Screenshot failed (code=$errorCode) for $currentMonitoredAppName ($currentMonitoredPackage), reason=$reason"
                            )
                        }
                    }
            )
        } catch (t: Throwable) {
            isTakingScreenshot = false
            Log.e("A11yService", "Screenshot exception for reason=$reason", t)
        }
    }

    private fun loadAiModelId(): String {
        val prefs = getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getString("model", "qwen3-vl-flash") ?: "qwen3-vl-flash"
    }

    private fun loadAiKey(): String {
        val prefs = getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getString("api_key", "") ?: ""
    }

    private fun loadAutoSaveScreenshot(): Boolean {
        val prefs = getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getBoolean("auto_save_screenshot", false)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 85, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private suspend fun saveBitmapToGallery(bitmap: Bitmap, reason: String) {
        try {
            val appName = currentMonitoredAppName ?: "Unknown"
            val timestamp = System.currentTimeMillis()
            val displayName = "SeeNot_${appName}_${timestamp}_$reason.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SeeNot")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: run {
                Log.e("A11yService", "Failed to create MediaStore entry")
                return
            }

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            } ?: run {
                Log.e("A11yService", "Failed to open output stream")
                contentResolver.delete(uri, null, null)
                return
            }

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, contentValues, null, null)

            Log.d("A11yService", "Screenshot saved to gallery: $displayName")
        } catch (e: Exception) {
            Log.e("A11yService", "Error saving screenshot to gallery", e)
        }
    }

    private fun describeImageWithAI(bitmap: Bitmap) {
        val apiKey = loadAiKey()
        val modelId = loadAiModelId()

        if (apiKey.isEmpty()) {
            Log.w("A11yService", "AI API key not configured, skipping image description")
            return
        }

        coroutineScope.launch {
            try {
                // Convert bitmap to base64
                val base64Image = bitmapToBase64(bitmap)
                Log.d("A11yService", "Image converted to base64, size: ${base64Image.length} chars")

                // Get activated rules only for the currently monitored app
                val currentAppName = currentMonitoredAppName
                if (currentAppName == null) {
                    Log.d("A11yService", "No currently monitored app, skipping AI evaluation")
                    bitmap.recycle()
                    return@launch
                }

                val monitoringApps = appDataStore.loadMonitoringApps()
                val activatedRules = mutableListOf<Pair<String, Rule>>()
                
                // Find the current monitored app and get its rules
                val currentApp = monitoringApps.find { it.name == currentAppName && it.isEnabled }
                if (currentApp != null) {
                    for (rule in currentApp.rules) {
                        // Only process conditions that can be evaluated visually
                        if (rule.condition.type == ConditionType.ON_PAGE || 
                            rule.condition.type == ConditionType.ON_CONTENT) {
                            activatedRules.add(Pair(currentApp.name, rule))
                        }
                    }
                }

                Log.d("A11yService", "Found ${activatedRules.size} activated rules to evaluate for app=$currentAppName")

                if (activatedRules.isEmpty()) {
                    Log.d("A11yService", "No activated rules found, skipping AI evaluation")
                    bitmap.recycle()
                    return@launch
                }

                // Create OpenAI client with custom base URL for DashScope
                val openAI = OpenAI(
                    token = apiKey,
                    timeout = Timeout(socket = 60.seconds),
                    host = OpenAIHost(baseUrl = AI_BASE_URL)
                )

                val imageContent = "data:image/png;base64,$base64Image"

                // Process each activated rule
                for ((appName, rule) in activatedRules) {
                    val startTime = System.currentTimeMillis()
                    try {
                        val condition = rule.condition
                        val action = rule.action
                        
                        // Build question from condition
                        val question = when (condition.type) {
                            ConditionType.ON_PAGE -> {
                                val pageName = condition.parameter ?: ""
                                "Is this the $pageName page?"
                            }
                            ConditionType.ON_CONTENT -> {
                                val contentTopic = condition.parameter ?: ""
                                "Is this content about $contentTopic?"
                            }
                            else -> null
                        }

                        if (question == null) {
                            continue
                        }

                        // Build app context with available information only
                        val contextParts = mutableListOf<String>()
                        if (currentMonitoredAppName != null && currentMonitoredPackage != null) {
                            contextParts.add("Current app: $currentMonitoredAppName (package: $currentMonitoredPackage)")
                        }
                        if (lastTimeCapableClass != null) {
                            contextParts.add("Current class: $lastTimeCapableClass")
                        }
                        
                        val appContext = if (contextParts.isNotEmpty()) {
                            contextParts.joinToString("\n")
                        } else {
                            null
                        }
                        
                        // Combine AI_PROMPT with the condition question and app context
                        val fullPrompt = if (appContext != null) {
                            "$AI_PROMPT\n\n$appContext\n\nUser's question: $question"
                        } else {
                            "$AI_PROMPT\n\nUser's question: $question"
                        }

                        // Create chat completion request
                        val contentList: List<ContentPart> = ContentPartBuilder().apply {
                            image(imageContent)
                            text(fullPrompt)
                        }.build()
                        
                        val request = ChatCompletionRequest(
                            model = ModelId(modelId),
                            messages = listOf(
                                ChatMessage(
                                    role = ChatRole.User,
                                    content = contentList
                                )
                            ),
                            maxTokens = 1000
                        )

                        // Call API
                        val response = openAI.chatCompletion(request)
                        val elapsedTime = System.currentTimeMillis() - startTime

                        // Extract and log response with both condition and action
                        val result = response.choices.firstOrNull()?.message?.content
                        if (result != null) {
                            Log.d("A11yService", "AI Result for app=$appName, question=$question, context=$appContext, condition=${condition.type}, action=${action.type}: $result, elapsed time: ${elapsedTime}ms")
                        } else {
                            Log.w("A11yService", "No result returned from AI for app=$appName, question=$question, context=$appContext, condition=${condition.type}, action=${action.type}, elapsed time: ${elapsedTime}ms")
                        }
                    } catch (e: Exception) {
                        val elapsedTime = System.currentTimeMillis() - startTime
                        Log.e("A11yService", "Error evaluating rule for app=$appName, condition=${rule.condition.type}, action=${rule.action.type} (elapsed: ${elapsedTime}ms)", e)
                        e.message?.let { 
                            Log.e("A11yService", "Error details: $it")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("A11yService", "Error in describeImageWithAI", e)
                e.message?.let { Log.e("A11yService", "Error details: $it") }
            } finally {
                bitmap.recycle()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "seenot_accessibility"
        private const val NOTIFICATION_ID = 1001
        const val LOG_INTERVAL_MS: Long = 5_000L
        // DashScope compatible mode endpoint
        const val AI_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/"
        const val AI_PROMPT = """
        You are a strict visual classifier. Only answer "yes" if the visual state is a PERFECT MATCH to the user's question. Otherwise answer "no".

        Rules:
        - Do NOT guess. When uncertain, answer "no".
        - Partial matches or related elements are "no".
        - If the visual state is not a PERFECT MATCH to the user's question, answer "no".
        - Examples:
        * If the question asks: "Is this a feed/list page showing image-note or video-note items?",
            then only a scrolling feed or grid/list overview of notes counts as "yes".
            A detail page of a single note, a player page, or any non-list page is "no".
        - Output format: strictly JSON {"reason": "brief reason in 10 words or less", "answer": "yes"} or {"reason": "brief reason in 10 words or less", "answer": "no"}.
        - The reason must be concise and explain why you chose yes or no.
        """
    }
}
