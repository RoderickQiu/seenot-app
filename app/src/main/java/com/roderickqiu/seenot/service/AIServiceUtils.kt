package com.roderickqiu.seenot.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ContentPartBuilder
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.roderickqiu.seenot.data.AIStatsRepo
import kotlin.text.RegexOption
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object AIServiceUtils {
    // DashScope compatible mode endpoint
    const val AI_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/"
    const val CONFIDENCE_THRESHOLD = 94.0
    
    /**
     * Detect if text is primarily Chinese or English.
     * Returns "zh" for Chinese, "en" for English (default).
     */
    fun detectLanguage(text: String): String {
        if (text.isEmpty()) return "en"
        
        var chineseCharCount = 0
        var totalCharCount = 0
        
        text.forEach { char ->
            val codePoint = char.code
            // Check for Chinese characters (CJK Unified Ideographs)
            if ((codePoint >= 0x4E00 && codePoint <= 0x9FFF) ||
                (codePoint >= 0x3400 && codePoint <= 0x4DBF) ||
                (codePoint >= 0x20000 && codePoint <= 0x2A6DF) ||
                (codePoint >= 0x2A700 && codePoint <= 0x2B73F) ||
                (codePoint >= 0x2B740 && codePoint <= 0x2B81F) ||
                (codePoint >= 0x2B820 && codePoint <= 0x2CEAF)) {
                chineseCharCount++
            }
            if (!char.isWhitespace()) {
                totalCharCount++
            }
        }
        
        // If more than 30% of non-whitespace characters are Chinese, consider it Chinese
        return if (totalCharCount > 0 && chineseCharCount * 100 / totalCharCount >= 30) {
            "zh"
        } else {
            "en"
        }
    }
    
    const val AI_PROMPT = """
        You are a strict visual classifier that outputs confidence scores. Given an image and a description, rate how confident you are that the image matches the description.

        Rules:
        - IGNORE any grey or semi-transparent toast/overlay at the top of the screen. Such overlays are from the capture tool, not the app. Base your confidence ONLY on the actual app content (the main screen underneath).
        - Output a confidence score from 0 to 100, where:
          * 0 means absolutely certain it does NOT match
          * 100 means absolutely certain it DOES match
          * 50 means completely uncertain
        - Consider partial matches: if some elements match but not all, give an intermediate score
        - Be calibrated: a score of 80 should mean you're right 80% of the time when you give that score
        - Output format: strictly JSON {"reason": "brief reason in 10~20 words", "confidence": <number from 0 to 100>}
        - The reason must be concise and explain your confidence level.
        """

    private const val IMPROVEMENT_PROMPT = """
        You are an expert at writing precise visual descriptions for screen matching. Your task is to improve a description so it better distinguishes between screens that should match and screens that should not match.

        Current description: "%s"
        App name: %s
        Package name: %s

        IMPORTANT LANGUAGE REQUIREMENT:
        - First, detect the language of the "Current description" above (it may be Chinese, English, or any other language).
        - You MUST output the improved_description in the SAME language as the input description.
        - If the input description is in Chinese, output improved_description in Chinese.
        - If the input description is in English, output improved_description in English.
        - If the input description is in any other language, output improved_description in that language.
        - Match the language style and writing conventions of the input description.

        I will show you images that failed to be correctly classified. Each image will be clearly labeled as either:
        - [POSITIVE EXAMPLE]: These screens SHOULD match the description but were incorrectly rejected (confidence too low). The improved description must include these patterns.
        - [NEGATIVE EXAMPLE]: These screens should NOT match the description but were incorrectly accepted (confidence too high). The improved description must exclude these patterns.
        
        IMPORTANT: Pay close attention to the [POSITIVE EXAMPLE] and [NEGATIVE EXAMPLE] labels before each image to understand what went wrong.

        Analyze the visual differences between these categories and write an improved description that:
        1. Captures the common visual patterns in positive examples
        2. Excludes the visual patterns unique to negative examples
        3. Is specific enough to avoid false positives
        4. Is general enough to capture all valid positive cases
        5. Focuses on visual elements, layout, text, icons, colors that distinguish the target screens
        6. Remember that the description should still be general enough to capture more cases, instead of exactly matching the examples
        7. You can utilize your knowledge of the given app (app name and package name provided above) to improve the description

        Output format: strictly JSON {"analysis": "brief analysis of what distinguishes positive from negative examples (you can write analysis in English)", "improved_description": "the new improved description (MUST be in the same language as the input description)"}
        """

    fun loadAiModelId(context: Context): String {
        val prefs = context.getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getString("model", "qwen3-vl-plus") ?: "qwen3-vl-plus"
    }

    fun loadNormalizationModelId(context: Context): String {
        val prefs = context.getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getString("normalization_model", "qwen-plus") ?: "qwen-plus"
    }

    fun loadAiKey(context: Context): String {
        val prefs = context.getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getString("api_key", "") ?: ""
    }

    fun loadShowRuleResultToast(context: Context): Boolean {
        val prefs = context.getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getBoolean("show_rule_result_toast", false)
    }

    fun loadEnableRuleRecording(context: Context): Boolean {
        val prefs = context.getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getBoolean("enable_rule_recording", true)
    }

    /** Screenshot save mode for rule records: "all", "matched_only", "none" */
    fun loadRuleRecordScreenshotMode(context: Context): String {
        val prefs = context.getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getString("rule_record_screenshot_mode", "all") ?: "all"
    }

    fun parseAIResult(result: String): Boolean {
        val confidence = parseConfidence(result)
        return confidence >= CONFIDENCE_THRESHOLD
    }

    fun parseConfidence(result: String): Double {
        try {
            // Try parsing as JSON first
            val jsonStart = result.indexOf('{')
            val jsonEnd = result.lastIndexOf('}')
            if (jsonStart != -1 && jsonEnd != -1 && jsonStart < jsonEnd) {
                val jsonString = result.substring(jsonStart, jsonEnd + 1)
                val jsonObject = JSONObject(jsonString)
                if (jsonObject.has("confidence")) {
                    return jsonObject.getDouble("confidence")
                }
            }
        } catch (e: Exception) {
            // Ignore JSON parse error, fall back to regex
        }

        // Fallback: regex search
        val confidencePattern = Regex("\"confidence\"\\s*:\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val match = confidencePattern.find(result)
        if (match != null) {
            val confidenceStr = match.groupValues[1]
            return confidenceStr.toDoubleOrNull() ?: 0.0
        }

        return 0.0
    }

    fun parseReason(result: String?): String? {
        if (result.isNullOrBlank()) return null
        try {
            val jsonStart = result.indexOf('{')
            val jsonEnd = result.lastIndexOf('}')
            if (jsonStart != -1 && jsonEnd != -1 && jsonStart < jsonEnd) {
                val jsonString = result.substring(jsonStart, jsonEnd + 1)
                val jsonObject = JSONObject(jsonString)
                if (jsonObject.has("reason")) {
                    return jsonObject.getString("reason")
                }
            }
        } catch (_: Exception) {
        }

        val reasonPattern = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val match = reasonPattern.find(result)
        if (match != null) {
            return match.groupValues[1]
        }
        return null
    }

    /**
     * Evaluate a description against a single image.
     * Returns a pair of (confidence score, error message if any).
     */
    suspend fun evaluateDescriptionOnImage(
        context: Context,
        bitmap: Bitmap,
        description: String
    ): Pair<Double, String?> = withContext(Dispatchers.IO) {
        val apiKey = loadAiKey(context)
        val modelId = loadAiModelId(context)
        val statsRepo = AIStatsRepo(context)

        if (apiKey.isEmpty()) {
            return@withContext Pair(0.0, "API key not configured")
        }

        val startTime = System.currentTimeMillis()
        var promptTokens: Int? = null
        var completionTokens: Int? = null

        try {
            val base64Image = BitmapUtils.bitmapToBase64(bitmap)
            val imageContent = "data:image/png;base64,$base64Image"

            val question = "Does the current screen match this description: $description?"
            val fullPrompt = "$AI_PROMPT\n\nUser's question: $question"

            val openAI = OpenAI(
                token = apiKey,
                timeout = Timeout(socket = 60.seconds),
                host = OpenAIHost(baseUrl = AI_BASE_URL)
            )

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

            val response = openAI.chatCompletion(request)
            val result = response.choices.firstOrNull()?.message?.content

            // Extract token usage if available
            response.usage?.let {
                promptTokens = it.promptTokens
                completionTokens = it.completionTokens
            }

            val latencyMs = System.currentTimeMillis() - startTime

            if (result != null) {
                val confidence = parseConfidence(result)
                // Record stats (1 rule match if confidence >= threshold)
                statsRepo.recordApiCall(
                    success = true,
                    latencyMs = latencyMs,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    matchedRules = if (confidence >= CONFIDENCE_THRESHOLD) 1 else 0
                )
                Pair(confidence, null)
            } else {
                statsRepo.recordApiCall(
                    success = false,
                    latencyMs = latencyMs,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    matchedRules = 0
                )
                Pair(0.0, "No response from AI")
            }
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            statsRepo.recordApiCall(
                success = false,
                latencyMs = latencyMs,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                matchedRules = 0
            )
            Log.e("AIServiceUtils", "Error evaluating description on image", e)
            Pair(0.0, e.message)
        }
    }

    /**
     * Generate an improved description based on failed examples.
     * Returns the improved description or null if generation failed.
     */
    suspend fun generateImprovedDescription(
        context: Context,
        currentDescription: String,
        failedPositives: List<Pair<Bitmap, Double>>,
        failedNegatives: List<Pair<Bitmap, Double>>,
        appName: String? = null,
        packageName: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = loadAiKey(context)
        val modelId = loadAiModelId(context)
        val statsRepo = AIStatsRepo(context)

        if (apiKey.isEmpty()) {
            return@withContext null
        }

        if (failedPositives.isEmpty() && failedNegatives.isEmpty()) {
            return@withContext null
        }

        val startTime = System.currentTimeMillis()
        var success = false
        var promptTokens: Int? = null
        var completionTokens: Int? = null

        try {
            val openAI = OpenAI(
                token = apiKey,
                timeout = Timeout(socket = 120.seconds),
                host = OpenAIHost(baseUrl = AI_BASE_URL)
            )

            val contentBuilder = ContentPartBuilder()

            // Add base prompt with app information (always use English prompt)
            val appNameDisplay = appName ?: "Unknown"
            val packageNameDisplay = packageName ?: "Unknown"
            val basePrompt = String.format(IMPROVEMENT_PROMPT, currentDescription, appNameDisplay, packageNameDisplay)
            contentBuilder.text(basePrompt)

            // Add failed positive examples (limit to 3 to avoid token limits)
            if (failedPositives.isNotEmpty()) {
                contentBuilder.text("\n\n=== POSITIVE EXAMPLES (should match but didn't) ===")
                contentBuilder.text("\nThese screens SHOULD match the description but were incorrectly rejected.")
                contentBuilder.text("\nThe current description failed to recognize them (confidence was too low).")
                failedPositives.take(3).forEachIndexed { index, (bitmap, confidence) ->
                    val base64 = BitmapUtils.bitmapToBase64(bitmap)
                    contentBuilder.text("\n[POSITIVE EXAMPLE ${index + 1}] This screen should match the description but got confidence ${"%.1f".format(confidence)} (needs >= $CONFIDENCE_THRESHOLD):")
                    contentBuilder.image("data:image/png;base64,$base64")
                }
            }

            // Add failed negative examples (limit to 3 to avoid token limits)
            if (failedNegatives.isNotEmpty()) {
                contentBuilder.text("\n\n=== NEGATIVE EXAMPLES (matched but shouldn't) ===")
                contentBuilder.text("\nThese screens should NOT match the description but were incorrectly accepted.")
                contentBuilder.text("\nThe current description incorrectly matched them (confidence was too high).")
                failedNegatives.take(3).forEachIndexed { index, (bitmap, confidence) ->
                    val base64 = BitmapUtils.bitmapToBase64(bitmap)
                    contentBuilder.text("\n[NEGATIVE EXAMPLE ${index + 1}] This screen should NOT match the description but got confidence ${"%.1f".format(confidence)} (should be < $CONFIDENCE_THRESHOLD):")
                    contentBuilder.image("data:image/png;base64,$base64")
                }
            }

            contentBuilder.text("\n\nNow analyze the examples and provide an improved description.")

            val contentList = contentBuilder.build()

            val request = ChatCompletionRequest(
                model = ModelId(modelId),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.User,
                        content = contentList
                    )
                ),
                maxTokens = 2000
            )

            val response = openAI.chatCompletion(request)
            val result = response.choices.firstOrNull()?.message?.content

            // Extract token usage if available
            response.usage?.let {
                promptTokens = it.promptTokens
                completionTokens = it.completionTokens
            }

            val latencyMs = System.currentTimeMillis() - startTime
            success = result != null

            // Record stats (no rule matches for description improvement calls)
            statsRepo.recordApiCall(
                success = success,
                latencyMs = latencyMs,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                matchedRules = 0
            )

            if (result != null) {
                // Parse the improved description from JSON response
                val jsonStart = result.indexOf('{')
                val jsonEnd = result.lastIndexOf('}')
                if (jsonStart != -1 && jsonEnd != -1 && jsonStart < jsonEnd) {
                    val jsonString = result.substring(jsonStart, jsonEnd + 1)
                    val jsonObject = JSONObject(jsonString)
                    if (jsonObject.has("improved_description")) {
                        return@withContext jsonObject.getString("improved_description")
                    }
                }
                // Fallback: try to extract using regex
                val descPattern = Regex("\"improved_description\"\\s*:\\s*\"([^\"]+)\"")
                val match = descPattern.find(result)
                if (match != null) {
                    return@withContext match.groupValues[1]
                }
            }
            null
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            statsRepo.recordApiCall(
                success = false,
                latencyMs = latencyMs,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                matchedRules = 0
            )
            Log.e("AIServiceUtils", "Error generating improved description", e)
            null
        }
    }
}

