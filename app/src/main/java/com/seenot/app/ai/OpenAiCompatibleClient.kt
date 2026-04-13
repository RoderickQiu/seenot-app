package com.seenot.app.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.seenot.app.R
import com.seenot.app.config.ApiConfig
import com.seenot.app.config.ApiSettings
import com.seenot.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiCompatibleClient(
    private val settingsProvider: () -> ApiSettings = { ApiConfig.getSettings() }
) {
    companion object {
        private const val TAG = "OpenAiCompatClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun completeText(
        systemPrompt: String? = null,
        userPrompt: String,
        temperature: Double = 0.3,
        maxTokens: Int = 800,
        modelOverride: String? = null
    ): String = withContext(Dispatchers.IO) {
        val settings = settingsProvider()
        val model = modelOverride?.takeIf { it.isNotBlank() } ?: settings.model
        val requestBody = buildRequestBody(
            settings = settings,
            model = model,
            temperature = temperature,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageDataUrl = null
        )
        execute(settings, requestBody)
    }

    suspend fun completeVision(
        userPrompt: String,
        imageDataUrl: String,
        systemPrompt: String? = null,
        temperature: Double = 0.3,
        maxTokens: Int = 1000,
        modelOverride: String? = null
    ): String = withContext(Dispatchers.IO) {
        val settings = settingsProvider()
        val model = modelOverride?.takeIf { it.isNotBlank() } ?: settings.model
        val requestBody = buildRequestBody(
            settings = settings,
            model = model,
            temperature = temperature,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            imageDataUrl = imageDataUrl
        )
        execute(settings, requestBody)
    }

    private fun buildRequestBody(
        settings: ApiSettings,
        model: String,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?,
        userPrompt: String,
        imageDataUrl: String?
    ): JsonObject {
        val root = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", temperature)
            addProperty("max_tokens", maxTokens)
            add("messages", JsonArray().apply {
                if (!systemPrompt.isNullOrBlank()) {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                }

                add(JsonObject().apply {
                    addProperty("role", "user")
                    if (imageDataUrl == null) {
                        addProperty("content", userPrompt)
                    } else {
                        add("content", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("type", "text")
                                addProperty("text", userPrompt)
                            })
                            add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply {
                                    addProperty("url", imageDataUrl)
                                })
                            })
                        })
                    }
                })
            })
        }

        ApiConfig.providerExtraBody(
            provider = settings.provider,
            model = model
        )?.entrySet()?.forEach { (key, value) ->
            root.add(key, value)
        }

        return root
    }

    private fun execute(settings: ApiSettings, body: JsonObject): String {
        val baseUrl = settings.baseUrl.trim().trimEnd('/')
        val apiKey = settings.apiKey.trim()
        require(baseUrl.isNotBlank()) { "Base URL not configured" }
        require(apiKey.isNotBlank()) { "API key not configured" }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Logger.e(TAG, "HTTP ${response.code}: $responseText")
                throw IllegalStateException("模型请求失败 (${response.code})")
            }
            return extractAssistantText(responseText)
        }
    }

    private fun extractAssistantText(responseText: String): String {
        val root = JsonParser.parseString(responseText).asJsonObject
        val choices = root.getAsJsonArray("choices")
            ?: throw IllegalStateException("模型返回为空")
        if (choices.size() == 0) {
            throw IllegalStateException("模型返回为空")
        }
        val firstChoice = choices.get(0).asJsonObject
        val message = firstChoice.getAsJsonObject("message")
            ?: throw IllegalStateException("模型返回缺少 message")
        val content = message.get("content") ?: throw IllegalStateException("模型返回缺少 content")
        return content.toAssistantText()
    }

    private fun JsonElement.toAssistantText(): String {
        return when {
            isJsonNull -> ""
            isJsonPrimitive -> asString
            isJsonArray -> asJsonArray.mapNotNull { element ->
                if (!element.isJsonObject) {
                    return@mapNotNull element.takeIf { it.isJsonPrimitive }?.asString
                }
                val obj = element.asJsonObject
                when {
                    obj.has("text") -> obj.get("text").asString
                    obj.has("content") -> obj.get("content").asString
                    else -> null
                }
            }.joinToString("\n")
            isJsonObject -> {
                val obj = asJsonObject
                when {
                    obj.has("text") -> obj.get("text").asString
                    obj.has("content") -> obj.get("content").asString
                    else -> toString()
                }
            }
            else -> toString()
        }
    }
}
