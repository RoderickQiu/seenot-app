package com.roderickqiu.seenot.service

import android.content.Context
import android.util.Log
import kotlin.text.RegexOption
import org.json.JSONObject

object AIServiceUtils {
    // DashScope compatible mode endpoint
    const val AI_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/"
    const val CONFIDENCE_THRESHOLD = 94.0
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

    fun loadAiModelId(context: Context): String {
        val prefs = context.getSharedPreferences("seenot_ai", Context.MODE_PRIVATE)
        return prefs.getString("model", "qwen3-vl-plus") ?: "qwen3-vl-plus"
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
}

