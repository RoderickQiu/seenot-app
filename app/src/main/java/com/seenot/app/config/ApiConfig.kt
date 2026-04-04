package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.JsonObject

/**
 * API Configuration for SeeNot
 * Stores API keys and endpoints in encrypted SharedPreferences
 */
object ApiConfig {
    private const val PREFS_NAME = "api_config_secure"
    private const val KEY_API_KEY_PREFIX = "api_key_"
    private const val KEY_BASE_URL = "openai_base_url"
    private const val KEY_PROVIDER = "model_provider"
    private const val KEY_MODEL = "model"
    private const val KEY_QWEN_REGION = "qwen_region"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(provider: AiProvider = getProvider()): String {
        return prefs?.getString("$KEY_API_KEY_PREFIX${provider.name}", "") ?: ""
    }

    fun setApiKey(provider: AiProvider = getProvider(), key: String) {
        prefs?.edit()?.putString("$KEY_API_KEY_PREFIX${provider.name}", key)?.apply()
    }

    fun getBaseUrl(): String {
        val provider = getProvider()
        val fallback = if (provider == AiProvider.DASHSCOPE) {
            getQwenRegion().baseUrl
        } else {
            provider.defaultBaseUrl
        }
        return prefs?.getString(KEY_BASE_URL, fallback) ?: fallback
    }

    fun setBaseUrl(url: String) {
        prefs?.edit()?.putString(KEY_BASE_URL, url)?.apply()
    }

    fun getProvider(): AiProvider {
        val raw = prefs?.getString(KEY_PROVIDER, AiProvider.DASHSCOPE.name)
        return raw?.let { value ->
            AiProvider.entries.firstOrNull { it.name == value }
        } ?: AiProvider.DASHSCOPE
    }

    fun setProvider(provider: AiProvider) {
        prefs?.edit()?.putString(KEY_PROVIDER, provider.name)?.apply()
    }

    fun getModel(): String {
        return prefs?.getString(KEY_MODEL, getProvider().defaultModel)
            ?: getProvider().defaultModel
    }

    fun setModel(model: String) {
        prefs?.edit()?.putString(KEY_MODEL, model.trim())?.apply()
    }

    fun getQwenRegion(): QwenRegion {
        val raw = prefs?.getString(KEY_QWEN_REGION, QwenRegion.BEIJING.name)
        return raw?.let { value ->
            QwenRegion.entries.firstOrNull { it.name == value }
        } ?: QwenRegion.BEIJING
    }

    fun setQwenRegion(region: QwenRegion) {
        prefs?.edit()
            ?.putString(KEY_QWEN_REGION, region.name)
            ?.putString(KEY_BASE_URL, region.baseUrl)
            ?.apply()
    }

    fun getSettings(): ApiSettings {
        return ApiSettings(
            provider = getProvider(),
            apiKey = getApiKey(),
            baseUrl = getBaseUrl(),
            model = getModel(),
            qwenRegion = getQwenRegion()
        )
    }

    fun saveSettings(settings: ApiSettings) {
        prefs?.edit()
            ?.putString(KEY_PROVIDER, settings.provider.name)
            ?.putString(KEY_BASE_URL, settings.baseUrl.trim())
            ?.putString(KEY_MODEL, settings.model.trim())
            ?.putString(KEY_QWEN_REGION, settings.qwenRegion.name)
            ?.apply()
        setApiKey(settings.provider, settings.apiKey.trim())
    }

    fun isConfigured(): Boolean {
        return getApiKey().isNotBlank()
    }

    fun providerExtraBody(
        provider: AiProvider,
        model: String
    ): JsonObject? {
        return when (provider) {
            AiProvider.DASHSCOPE -> JsonObject().apply {
                addProperty("enable_thinking", false)
            }
            AiProvider.OPENAI -> {
                val normalizedModel = model.trim().lowercase()
                if (!normalizedModel.startsWith("gpt-5")) {
                    null
                } else {
                    JsonObject().apply {
                        addProperty(
                            "reasoning_effort",
                            if (supportsOpenAiReasoningNone(normalizedModel)) "none" else "minimal"
                        )
                    }
                }
            }
            AiProvider.ANTHROPIC -> JsonObject().apply {
                add("thinking", JsonObject().apply {
                    addProperty("type", "disabled")
                })
            }
            AiProvider.GLM -> JsonObject().apply {
                add("thinking", JsonObject().apply {
                    addProperty("type", "disabled")
                })
            }
            AiProvider.GEMINI -> {
                if (model.startsWith("gemini-2.5-flash")) {
                    JsonObject().apply {
                        addProperty("reasoning_effort", "none")
                    }
                } else if (model.startsWith("gemini-3") || model.startsWith("gemini-2.5-pro")) {
                    JsonObject().apply {
                        addProperty("reasoning_effort", "minimal")
                    }
                } else {
                    null
                }
            }
            AiProvider.CUSTOM -> null
        }
    }

    private fun supportsOpenAiReasoningNone(model: String): Boolean {
        val minorVersion = Regex("""^gpt-5\.(\d+)""")
            .find(model)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return minorVersion != null && minorVersion >= 1
    }
}

enum class AiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String
) {
    DASHSCOPE(
        displayName = "DashScope / Qwen",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3.6-plus"
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-5.4-mini"
    ),
    GEMINI(
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-3.1-flash-preview"
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-sonnet-4.6"
    ),
    GLM(
        displayName = "Zhipu GLM",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-5v-turbo"
    ),
    CUSTOM(
        displayName = "自定义 OpenAI 兼容",
        defaultBaseUrl = "",
        defaultModel = ""
    )
}

data class ApiSettings(
    val provider: AiProvider,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val qwenRegion: QwenRegion
) {
    companion object {
        fun defaults(provider: AiProvider): ApiSettings {
            return ApiSettings(
                provider = provider,
                apiKey = "",
                baseUrl = provider.defaultBaseUrl,
                model = provider.defaultModel,
                qwenRegion = QwenRegion.BEIJING
            )
        }
    }
}

enum class QwenRegion(
    val displayName: String,
    val baseUrl: String
) {
    BEIJING(
        displayName = "中国大陆（北京）",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    ),
    SINGAPORE(
        displayName = "国际（Singapore）",
        baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    ),
    VIRGINIA(
        displayName = "美东（Virginia）",
        baseUrl = "https://dashscope-us.aliyuncs.com/compatible-mode/v1"
    )
}

data class ModelPreset(
    val id: String,
    val label: String,
    val model: String,
    val note: String = ""
)

fun recommendedModelPresets(
    provider: AiProvider,
    qwenRegion: QwenRegion = QwenRegion.BEIJING
): List<ModelPreset> {
    return when (provider) {
        AiProvider.DASHSCOPE -> when (qwenRegion) {
            QwenRegion.BEIJING -> listOf(
                ModelPreset("qwen36plus-cn", "Qwen 3.6 Plus", "qwen3.6-plus", note = "推荐"),
                ModelPreset("qwen35plus-cn", "Qwen 3.5 Plus", "qwen3.5-plus"),
                ModelPreset("qwen35flash-cn", "Qwen 3.5 Flash", "qwen3.5-flash"),
                ModelPreset("qwenvlplus-cn", "Qwen VL Plus", "qwen-vl-plus")
            )
            QwenRegion.SINGAPORE -> listOf(
                ModelPreset("qwen36plus-sg", "Qwen 3.6 Plus", "qwen3.6-plus", note = "推荐"),
                ModelPreset("qwen35plus-sg", "Qwen 3.5 Plus", "qwen3.5-plus"),
                ModelPreset("qwen35flash-sg", "Qwen 3.5 Flash", "qwen3.5-flash"),
                ModelPreset("qwenvlplus-sg", "Qwen VL Plus", "qwen-vl-plus"),
                ModelPreset("qwenvlmax-sg", "Qwen VL Max", "qwen-vl-max")
            )
            QwenRegion.VIRGINIA -> listOf(
                ModelPreset("qwen35plus-us", "Qwen 3.5 Plus", "qwen3.5-plus"),
                ModelPreset("qwen35flash-us", "Qwen 3.5 Flash", "qwen3.5-flash")
            )
        }
        AiProvider.OPENAI -> listOf(
            ModelPreset("gpt54mini", "GPT 5.4 Mini", "gpt-5.4-mini", note = "推荐"),
            ModelPreset("gpt5mini", "GPT 5 Mini", "gpt-5-mini")
        )
        AiProvider.GEMINI -> listOf(
            ModelPreset("gem31flash", "Gemini 3 Flash", "gemini-3-flash-preview", note = "推荐"),
            ModelPreset("gem31flashlite", "Gemini 3.1 Flash Lite", "gemini-3.1-flash-lite-preview"),
            ModelPreset("gem25flash", "Gemini 2.5 Flash", "gemini-2.5-flash")
        )
        AiProvider.ANTHROPIC -> listOf(
            ModelPreset("claudesonnet46", "Claude Sonnet 4.6", "claude-sonnet-4-6", note = "推荐"),
            ModelPreset("claudehaiku45", "Claude Haiku 4.5", "claude-haiku-4-5")
        )
        AiProvider.GLM -> listOf(
            ModelPreset("glm5vturbo", "GLM 5V Turbo", "glm-5v-turbo", note = "推荐"),
            ModelPreset("glm5", "GLM 5", "glm-5"),
            ModelPreset("glm46vflashx", "GLM 4.6V Flash X", "glm-4.6v-flashx"),
            ModelPreset("glm46v", "GLM 4.6V", "glm-4.6v")
        )
        AiProvider.CUSTOM -> emptyList()
    }
}
