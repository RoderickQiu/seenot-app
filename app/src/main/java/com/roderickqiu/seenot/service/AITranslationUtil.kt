package com.roderickqiu.seenot.service

import android.content.Context
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import com.roderickqiu.seenot.utils.Logger
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

/**
 * Reusable AI translation utility. All calls are suspend and use the same
 * API key / model as the normalization pipeline.
 *
 * Callers can inject a [note] to steer output style, e.g.:
 *   "Keep each translation very brief, 2-4 Chinese characters preferred."
 */
object AITranslationUtil {
    private const val TAG = "AITranslationUtil"

    /**
     * Translate a list of strings in a single API call.
     * Returns a map of original -> translated.
     * Falls back to the original string on any failure.
     *
     * @param context    Android context (used to load API key / model ID)
     * @param items      Strings to translate
     * @param targetLanguage  Human-readable target language, e.g. "Simplified Chinese", "Japanese"
     * @param note       Optional extra instruction injected into the prompt
     */
    suspend fun translateBatch(
        context: Context,
        items: List<String>,
        targetLanguage: String,
        note: String? = null
    ): Map<String, String> {
        if (items.isEmpty()) return emptyMap()

        val apiKey = AIServiceUtils.loadAiKey(context)
        if (apiKey.isBlank()) return items.associateWith { it }

        val keyed = items.mapIndexed { i, s -> "item_$i" to s }
        val inputJson = keyed.joinToString(", ", "{", "}") { (k, v) ->
            "\"$k\": ${JSONObject.quote(v)}"
        }

        val prompt = buildString {
            appendLine("Translate the following strings into $targetLanguage.")
            if (note != null) appendLine("Note: $note")
            appendLine("Return strict JSON only, preserving every key exactly.")
            appendLine("Input:")
            appendLine(inputJson)
            appendLine("Output format: {\"item_0\": \"...\", \"item_1\": \"...\", ...}")
        }

        return try {
            val modelId = AIServiceUtils.loadNormalizationModelId(context)
            val openAI = OpenAI(
                token = apiKey,
                timeout = Timeout(socket = 60.seconds),
                host = OpenAIHost(baseUrl = AIServiceUtils.AI_BASE_URL)
            )
            val request = ChatCompletionRequest(
                model = ModelId(modelId),
                messages = listOf(ChatMessage(role = ChatRole.User, content = prompt)),
                maxTokens = 800
            )
            val raw = openAI.chatCompletion(request)
                .choices.firstOrNull()?.message?.content.orEmpty()

            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            val json = if (start >= 0 && end > start) {
                JSONObject(cleaned.substring(start, end + 1))
            } else {
                JSONObject(cleaned)
            }

            keyed.associate { (k, original) ->
                original to (json.optString(k).takeIf { it.isNotBlank() } ?: original)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "translateBatch failed", e)
            items.associateWith { it }
        }
    }

    /**
     * Translate a single string.
     * Returns null on failure so callers can fall back gracefully.
     */
    suspend fun translate(
        context: Context,
        text: String,
        targetLanguage: String,
        note: String? = null
    ): String? {
        val result = translateBatch(context, listOf(text), targetLanguage, note)
        val translated = result[text]
        return if (translated != null && translated != text) translated else null
    }
}
