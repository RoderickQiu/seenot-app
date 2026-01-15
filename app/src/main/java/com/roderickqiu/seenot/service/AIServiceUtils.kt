package com.roderickqiu.seenot.service

import android.content.Context
import android.util.Log
import kotlin.text.RegexOption

object AIServiceUtils {
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
        // Parse AI result (assuming JSON format: {"answer": "yes"/"no", "reason": "..."})
        val yesPattern = Regex("\"answer\"\\s*:\\s*\"yes\"", RegexOption.IGNORE_CASE)
        val yesPattern2 = Regex("answer.*yes", RegexOption.IGNORE_CASE)
        return yesPattern.containsMatchIn(result) || yesPattern2.containsMatchIn(result)
    }
}

