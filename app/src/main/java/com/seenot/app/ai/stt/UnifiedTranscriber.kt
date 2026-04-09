package com.seenot.app.ai.stt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.seenot.app.config.AiProvider
import com.seenot.app.config.ApiConfig
import com.seenot.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class UnifiedTranscriber {
    companion object {
        private const val TAG = "UnifiedTranscriber"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audioFile: File): SttResult = withContext(Dispatchers.IO) {
        val sttSettings = ApiConfig.getSttSettings()
        val sttBaseUrl = sttSettings.baseUrl.trim().trimEnd('/')
        val sttApiKey = sttSettings.apiKey.trim()

        when (sttSettings.provider) {
            AiProvider.OPENAI, AiProvider.GLM, AiProvider.CUSTOM ->
                transcribeWithAudioEndpoint(
                    audioFile = audioFile,
                    baseUrl = sttBaseUrl,
                    apiKey = sttApiKey,
                    model = sttSettings.model
                )
            AiProvider.GEMINI -> SttResult.Error("Gemini 当前不走 UnifiedTranscriber")
            AiProvider.DASHSCOPE -> SttResult.Error("DashScope 当前不走 UnifiedTranscriber")
            AiProvider.ANTHROPIC -> SttResult.Error("Anthropic 目前不支持语音转写")
        }
    }

    private fun transcribeWithAudioEndpoint(
        audioFile: File,
        baseUrl: String,
        apiKey: String,
        model: String
    ): SttResult {
        val normalizedModel = model.trim()
        if (baseUrl.isBlank() || apiKey.isBlank() || normalizedModel.isBlank()) {
            return SttResult.Error("语音转写配置不完整")
        }

        val fileBody = audioFile.asRequestBody("application/octet-stream".toMediaType())
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", normalizedModel)
            .apply {
                if (ApiConfig.getSttProvider() == AiProvider.GLM) {
                    addFormDataPart("stream", "false")
                }
            }
            .addFormDataPart("file", audioFile.name, fileBody)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Logger.e(TAG, "audio/transcriptions failed ${response.code}: $text")
                return@use SttResult.Error("语音转写失败 (${response.code})")
            }

            val transcript = runCatching {
                JsonParser.parseString(text).asJsonObject.get("text")?.asString.orEmpty()
            }.getOrDefault("")

            if (transcript.isBlank()) {
                SttResult.Error("语音转写结果为空")
            } else {
                SttResult.Success(text = transcript, language = "auto")
            }
        }
    }
}
