package com.seenot.app.config

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.JsonObject
import com.seenot.app.R

/**
 * API Configuration for SeeNot
 * Stores API keys and endpoints in encrypted SharedPreferences
 */
object ApiConfig {
    private const val PREFS_NAME = "api_config_secure"
    private const val KEY_API_KEY_PREFIX = "api_key_"
    private const val KEY_BASE_URL_PREFIX = "base_url_"
    private const val KEY_BASE_URL_LEGACY = "openai_base_url"
    private const val KEY_PROVIDER = "model_provider"
    private const val KEY_MODEL = "model"
    private const val KEY_FEEDBACK_MODEL = "feedback_model"
    private const val KEY_QWEN_REGION = "qwen_region"
    private const val KEY_STT_PROVIDER = "stt_provider"
    private const val KEY_STT_MODEL = "stt_model"
    private const val KEY_STT_API_KEY = "stt_api_key"
    private const val KEY_STT_BASE_URL = "stt_base_url"
    private const val KEY_DEV_DASHSCOPE_KEY = "dev_dashscope_injected_key"
    private const val KEY_DEV_DASHSCOPE_VALID_UNTIL_MS = "dev_dashscope_injected_valid_until_ms"
    private const val KEY_AI_SOURCE = "ai_source"
    private const val KEY_MANAGED_AI_API_KEY = "managed_ai_api_key"
    private const val KEY_MANAGED_AI_BASE_URL = "managed_ai_base_url"
    private const val KEY_MANAGED_AI_MODEL = "managed_ai_model"
    private const val KEY_MANAGED_AI_VALID_UNTIL_MS = "managed_ai_valid_until_ms"

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

    fun clearForTest() {
        prefs?.edit()?.clear()?.commit()
    }

    fun getApiKey(provider: AiProvider = getProvider()): String {
        if (provider == AiProvider.DASHSCOPE) {
            getManagedAiSettingsIfActive()?.let { return it.apiKey }
        }
        return prefs?.getString("$KEY_API_KEY_PREFIX${provider.name}", "") ?: ""
    }

    fun setApiKey(provider: AiProvider = getProvider(), key: String) {
        val trimmed = key.trim()
        prefs?.edit()?.putString("$KEY_API_KEY_PREFIX${provider.name}", trimmed)?.apply()
        if (provider == AiProvider.DASHSCOPE) {
            val injectedKey = prefs?.getString(KEY_DEV_DASHSCOPE_KEY, "")?.trim().orEmpty()
            if (injectedKey.isNotBlank() && trimmed != injectedKey) {
                prefs?.edit()
                    ?.remove(KEY_DEV_DASHSCOPE_KEY)
                    ?.remove(KEY_DEV_DASHSCOPE_VALID_UNTIL_MS)
                    ?.apply()
            }
        }
    }

    fun getBaseUrl(provider: AiProvider = getProvider()): String {
        if (provider == AiProvider.DASHSCOPE) {
            getManagedAiSettingsIfActive()?.let { return it.baseUrl }
        }
        val fallback = if (provider == AiProvider.DASHSCOPE) {
            getQwenRegion().baseUrl
        } else {
            provider.defaultBaseUrl
        }
        val providerScoped = prefs?.getString("$KEY_BASE_URL_PREFIX${provider.name}", null)?.trim()
        if (!providerScoped.isNullOrBlank()) return providerScoped

        val legacy = prefs?.getString(KEY_BASE_URL_LEGACY, null)?.trim()
        return if (!legacy.isNullOrBlank() && provider == getProvider()) legacy else fallback
    }

    fun setBaseUrl(provider: AiProvider = getProvider(), url: String) {
        prefs?.edit()
            ?.putString("$KEY_BASE_URL_PREFIX${provider.name}", url.trim())
            ?.apply()
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
        getManagedAiSettingsIfActive()?.let { return it.model }
        return prefs?.getString(KEY_MODEL, getProvider().defaultModel)
            ?: getProvider().defaultModel
    }

    fun setModel(model: String) {
        prefs?.edit()?.putString(KEY_MODEL, model.trim())?.apply()
    }

    fun getFeedbackModel(): String {
        val fallback = getProvider().defaultFeedbackModel
        val raw = prefs?.getString(KEY_FEEDBACK_MODEL, fallback)?.trim().orEmpty()
        return if (raw.isBlank()) fallback else raw
    }

    fun setFeedbackModel(model: String) {
        prefs?.edit()?.putString(KEY_FEEDBACK_MODEL, model.trim())?.apply()
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
            ?.apply()
        setBaseUrl(AiProvider.DASHSCOPE, region.baseUrl)
    }

    fun getSettings(): ApiSettings {
        getManagedAiSettingsIfActive()?.let { return it }
        return getOwnKeySettings()
    }

    fun getOwnKeySettings(): ApiSettings {
        val provider = getProvider()
        return ApiSettings(
            provider = provider,
            apiKey = getOwnApiKey(provider),
            baseUrl = getOwnBaseUrl(provider),
            model = getStoredModel(),
            feedbackModel = getFeedbackModel(),
            qwenRegion = getQwenRegion()
        )
    }

    fun getOwnApiKey(provider: AiProvider): String = getStoredApiKey(provider)

    fun getOwnBaseUrl(provider: AiProvider): String = getStoredBaseUrl(provider)

    fun saveSettings(settings: ApiSettings) {
        prefs?.edit()
            ?.putString(KEY_PROVIDER, settings.provider.name)
            ?.putString(KEY_MODEL, settings.model.trim())
            ?.putString(KEY_FEEDBACK_MODEL, settings.feedbackModel.trim())
            ?.putString(KEY_QWEN_REGION, settings.qwenRegion.name)
            ?.apply()
        setApiKey(settings.provider, settings.apiKey.trim())
        setBaseUrl(settings.provider, settings.baseUrl.trim())
    }

    fun getAiSource(): AiSource {
        val raw = prefs?.getString(KEY_AI_SOURCE, AiSource.BRING_YOUR_OWN_KEY.name)
        return raw?.let { value ->
            AiSource.entries.firstOrNull { it.name == value }
        } ?: AiSource.BRING_YOUR_OWN_KEY
    }

    fun preferBringYourOwnKey() {
        prefs?.edit()
            ?.putString(KEY_AI_SOURCE, AiSource.BRING_YOUR_OWN_KEY.name)
            ?.apply()
        clearManagedAiSession()
    }

    fun saveManagedAiSession(
        apiKey: String,
        baseUrl: String,
        model: String,
        expiresAtEpochSeconds: Long
    ) {
        prefs?.edit()
            ?.putString(KEY_AI_SOURCE, AiSource.SEENOT_AI.name)
            ?.putString(KEY_MANAGED_AI_API_KEY, apiKey.trim())
            ?.putString(KEY_MANAGED_AI_BASE_URL, baseUrl.trim())
            ?.putString(KEY_MANAGED_AI_MODEL, model.trim())
            ?.putLong(KEY_MANAGED_AI_VALID_UNTIL_MS, expiresAtEpochSeconds * 1000L)
            ?.apply()
    }

    fun clearManagedAiSession() {
        prefs?.edit()
            ?.remove(KEY_MANAGED_AI_API_KEY)
            ?.remove(KEY_MANAGED_AI_BASE_URL)
            ?.remove(KEY_MANAGED_AI_MODEL)
            ?.remove(KEY_MANAGED_AI_VALID_UNTIL_MS)
            ?.apply()
    }

    fun isManagedAiActive(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return getManagedAiSettingsIfActive(nowEpochMs) != null
    }

    private fun getManagedAiSettingsIfActive(nowEpochMs: Long = System.currentTimeMillis()): ApiSettings? {
        if (getAiSource() != AiSource.SEENOT_AI) return null
        val apiKey = prefs?.getString(KEY_MANAGED_AI_API_KEY, "")?.trim().orEmpty()
        val baseUrl = prefs?.getString(KEY_MANAGED_AI_BASE_URL, "")?.trim().orEmpty()
        val model = prefs?.getString(KEY_MANAGED_AI_MODEL, "")?.trim().orEmpty()
        val validUntilMs = prefs?.getLong(KEY_MANAGED_AI_VALID_UNTIL_MS, 0L) ?: 0L
        if (apiKey.isBlank() || baseUrl.isBlank() || model.isBlank() || validUntilMs <= nowEpochMs) {
            clearManagedAiSession()
            return null
        }
        return ApiSettings(
            provider = AiProvider.DASHSCOPE,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            feedbackModel = model,
            qwenRegion = getQwenRegion()
        )
    }

    private fun getStoredApiKey(provider: AiProvider): String {
        return prefs?.getString("$KEY_API_KEY_PREFIX${provider.name}", "") ?: ""
    }

    private fun getStoredBaseUrl(provider: AiProvider): String {
        val fallback = if (provider == AiProvider.DASHSCOPE) {
            getQwenRegion().baseUrl
        } else {
            provider.defaultBaseUrl
        }
        val providerScoped = prefs?.getString("$KEY_BASE_URL_PREFIX${provider.name}", null)?.trim()
        if (!providerScoped.isNullOrBlank()) return providerScoped

        val legacy = prefs?.getString(KEY_BASE_URL_LEGACY, null)?.trim()
        return if (!legacy.isNullOrBlank() && provider == getProvider()) legacy else fallback
    }

    private fun getStoredModel(): String {
        return prefs?.getString(KEY_MODEL, getProvider().defaultModel)
            ?: getProvider().defaultModel
    }

    fun getSttProvider(): AiProvider {
        val fallback = defaultSttProviderFor(getProvider())
        val raw = prefs?.getString(KEY_STT_PROVIDER, fallback.name)
        val provider = raw?.let { value ->
            AiProvider.entries.firstOrNull { it.name == value }
        } ?: fallback
        return if (provider.supportsStt()) provider else fallback
    }

    fun getSttModel(): String {
        val provider = getSttProvider()
        val fallback = defaultSttModelFor(provider)
        val raw = prefs?.getString(KEY_STT_MODEL, fallback)?.trim().orEmpty()
        return if (raw.isBlank()) fallback else normalizeSttModel(provider, raw)
    }

    fun getSttApiKey(provider: AiProvider = getSttProvider()): String {
        if (provider != AiProvider.CUSTOM) return getApiKey(provider)
        val dedicated = prefs?.getString(KEY_STT_API_KEY, "")?.trim().orEmpty()
        return dedicated
    }

    fun getSttBaseUrl(provider: AiProvider = getSttProvider()): String {
        if (provider != AiProvider.CUSTOM) return getBaseUrl(provider)
        val fallback = when (provider) {
            AiProvider.DASHSCOPE -> getQwenRegion().baseUrl
            AiProvider.CUSTOM -> ""
            else -> provider.defaultBaseUrl
        }
        val dedicated = prefs?.getString(KEY_STT_BASE_URL, "")?.trim().orEmpty()
        return dedicated.ifBlank { fallback }
    }

    fun getSttSettings(): SttSettings {
        val provider = getSttProvider()
        return SttSettings(
            provider = provider,
            model = getSttModel(),
            apiKey = getSttApiKey(provider),
            baseUrl = getSttBaseUrl(provider)
        )
    }

    fun saveSttSettings(settings: SttSettings) {
        val normalizedProvider = if (settings.provider.supportsStt()) settings.provider else defaultSttProviderFor(getProvider())
        val normalizedModel = normalizeSttModel(normalizedProvider, settings.model.trim())

        prefs?.edit()
            ?.putString(KEY_STT_PROVIDER, normalizedProvider.name)
            ?.putString(KEY_STT_MODEL, normalizedModel)
            ?.putString(
                KEY_STT_API_KEY,
                if (normalizedProvider == AiProvider.CUSTOM) settings.apiKey.trim() else ""
            )
            ?.putString(
                KEY_STT_BASE_URL,
                if (normalizedProvider == AiProvider.CUSTOM) settings.baseUrl.trim() else ""
            )
            ?.apply()
    }

    fun isConfigured(): Boolean = isVisionConfigured()

    fun isVisionConfigured(): Boolean {
        if (getAiSource() == AiSource.SEENOT_AI) return true
        val settings = getSettings()
        return settings.apiKey.isNotBlank() &&
            settings.baseUrl.isNotBlank() &&
            settings.model.isNotBlank()
    }

    fun isVoiceConfigured(): Boolean {
        if (getAiSource() == AiSource.SEENOT_AI) return true
        val settings = getSttSettings()
        val providerSupported = when (settings.provider) {
            AiProvider.DASHSCOPE,
            AiProvider.OPENAI,
            AiProvider.GLM,
            AiProvider.CUSTOM -> true
            AiProvider.GEMINI,
            AiProvider.ANTHROPIC -> false
        }
        if (!providerSupported) return false
        if (settings.apiKey.isBlank() || settings.model.isBlank()) return false
        return settings.provider == AiProvider.DASHSCOPE || settings.baseUrl.isNotBlank()
    }

    fun reconcileDevelopmentInjectedDashscopeKey(
        buildInjectedKey: String,
        developmentModeEnabled: Boolean,
        validUntilEpochMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        val injectedKey = prefs?.getString(KEY_DEV_DASHSCOPE_KEY, "")?.trim().orEmpty()
        val shouldUseInjectedKey = developmentModeEnabled &&
            buildInjectedKey.isNotBlank() &&
            validUntilEpochMs > nowEpochMs

        if (!shouldUseInjectedKey) {
            if (injectedKey.isNotBlank() && getApiKey(AiProvider.DASHSCOPE) == injectedKey) {
                setApiKey(AiProvider.DASHSCOPE, "")
            }
            prefs?.edit()
                ?.remove(KEY_DEV_DASHSCOPE_KEY)
                ?.remove(KEY_DEV_DASHSCOPE_VALID_UNTIL_MS)
                ?.apply()
            return
        }

        prefs?.edit()
            ?.putString(KEY_DEV_DASHSCOPE_KEY, buildInjectedKey)
            ?.putLong(KEY_DEV_DASHSCOPE_VALID_UNTIL_MS, validUntilEpochMs)
            ?.apply()

        if (getApiKey(AiProvider.DASHSCOPE).isBlank()) {
            setApiKey(AiProvider.DASHSCOPE, buildInjectedKey)
        }
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

    private fun defaultSttProviderFor(modelProvider: AiProvider): AiProvider {
        return if (modelProvider.supportsStt()) modelProvider else AiProvider.DASHSCOPE
    }

    private fun normalizeSttModel(provider: AiProvider, model: String): String {
        return if (model.isBlank()) defaultSttModelFor(provider) else model
    }

    private fun defaultSttModelFor(provider: AiProvider): String {
        return when (provider) {
            AiProvider.DASHSCOPE -> "fun-asr-realtime"
            AiProvider.OPENAI -> "gpt-4o-mini-transcribe"
            AiProvider.GEMINI -> "fun-asr-realtime"
            AiProvider.GLM -> "glm-asr-2512"
            AiProvider.CUSTOM -> "whisper-1"
            AiProvider.ANTHROPIC -> "fun-asr-realtime"
        }
    }
}

private fun AiProvider.supportsStt(): Boolean {
    return when (this) {
        AiProvider.DASHSCOPE,
        AiProvider.OPENAI,
        AiProvider.GLM,
        AiProvider.CUSTOM -> true
        AiProvider.GEMINI,
        AiProvider.ANTHROPIC -> false
    }
}

enum class AiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val defaultFeedbackModel: String = defaultModel,
    @StringRes val displayNameResId: Int
) {
    DASHSCOPE(
        displayName = "DashScope / Qwen",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen3.6-plus",
        defaultFeedbackModel = "qwen3.6-plus",
        displayNameResId = R.string.provider_dashscope
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-5.4-mini",
        defaultFeedbackModel = "gpt-5.5",
        displayNameResId = R.string.provider_openai
    ),
    GEMINI(
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-3.1-flash-preview",
        defaultFeedbackModel = "gemini-3.1-pro-preview",
        displayNameResId = R.string.provider_gemini
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModel = "claude-sonnet-4.6",
        defaultFeedbackModel = "claude-opus-4-7",
        displayNameResId = R.string.provider_anthropic
    ),
    GLM(
        displayName = "Zhipu GLM",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-5v-turbo",
        defaultFeedbackModel = "glm-5.1",
        displayNameResId = R.string.provider_glm
    ),
    CUSTOM(
        displayName = "Custom OpenAI Compatible",
        defaultBaseUrl = "",
        defaultModel = "",
        displayNameResId = R.string.provider_custom
    )
}

enum class AiSource {
    SEENOT_AI,
    BRING_YOUR_OWN_KEY
}

fun selectableProviders(current: AiProvider? = null): List<AiProvider> {
    val defaults = listOf(
        AiProvider.DASHSCOPE,
        AiProvider.OPENAI,
        AiProvider.GEMINI,
        AiProvider.ANTHROPIC,
        AiProvider.GLM,
        AiProvider.CUSTOM
    )
    return if (current != null && current !in defaults) defaults + current else defaults
}

fun selectableSttProviders(current: AiProvider? = null): List<AiProvider> {
    val defaults = listOf(
        AiProvider.DASHSCOPE,
        AiProvider.OPENAI,
        AiProvider.GLM,
        AiProvider.CUSTOM
    )
    return if (current != null && current !in defaults && current.supportsStt()) defaults + current else defaults
}

data class ApiSettings(
    val provider: AiProvider,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val feedbackModel: String,
    val qwenRegion: QwenRegion
) {
    companion object {
        fun defaults(provider: AiProvider): ApiSettings {
            return ApiSettings(
                provider = provider,
                apiKey = "",
                baseUrl = provider.defaultBaseUrl,
                model = provider.defaultModel,
                feedbackModel = provider.defaultFeedbackModel,
                qwenRegion = QwenRegion.BEIJING
            )
        }
    }
}

enum class QwenRegion(
    val displayName: String,
    val baseUrl: String,
    @StringRes val displayNameResId: Int
) {
    BEIJING(
        displayName = "China",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        displayNameResId = R.string.region_beijing
    ),
    SINGAPORE(
        displayName = "International",
        baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        displayNameResId = R.string.region_singapore
    ),
    VIRGINIA(
        displayName = "US East",
        baseUrl = "https://dashscope-us.aliyuncs.com/compatible-mode/v1",
        displayNameResId = R.string.region_virginia
    )
}

data class ModelPreset(
    val id: String,
    val label: String,
    val model: String,
    val note: String = "",
    @StringRes val noteResId: Int? = null
)

data class SttSettings(
    val provider: AiProvider,
    val model: String,
    val apiKey: String,
    val baseUrl: String
)

fun recommendedModelPresets(
    provider: AiProvider,
    qwenRegion: QwenRegion = QwenRegion.BEIJING
): List<ModelPreset> {
    return when (provider) {
        AiProvider.DASHSCOPE -> when (qwenRegion) {
            QwenRegion.BEIJING -> listOf(
                ModelPreset("qwen36plus-cn", "Qwen 3.6 Plus", "qwen3.6-plus", noteResId = R.string.model_note_recommended),
                ModelPreset("qwen36maxpreview-cn", "Qwen 3.6 Max Preview", "qwen3.6-max-preview"),
                ModelPreset("qwen35plus-cn", "Qwen 3.5 Plus", "qwen3.5-plus"),
                ModelPreset("qwen35flash-cn", "Qwen 3.5 Flash", "qwen3.5-flash"),
                ModelPreset("qwenvlplus-cn", "Qwen VL Plus", "qwen-vl-plus")
            )
            QwenRegion.SINGAPORE -> listOf(
                ModelPreset("qwen36plus-sg", "Qwen 3.6 Plus", "qwen3.6-plus", noteResId = R.string.model_note_recommended),
                ModelPreset("qwen36maxpreview-sg", "Qwen 3.6 Max Preview", "qwen3.6-max-preview"),
                ModelPreset("qwen35plus-sg", "Qwen 3.5 Plus", "qwen3.5-plus"),
                ModelPreset("qwen35flash-sg", "Qwen 3.5 Flash", "qwen3.5-flash"),
                ModelPreset("qwenvlplus-sg", "Qwen VL Plus", "qwen-vl-plus"),
                ModelPreset("qwenvlmax-sg", "Qwen VL Max", "qwen-vl-max")
            )
            QwenRegion.VIRGINIA -> listOf(
                ModelPreset("qwen36plus-us", "Qwen 3.6 Plus", "qwen3.6-plus", noteResId = R.string.model_note_recommended),
                ModelPreset("qwen36maxpreview-us", "Qwen 3.6 Max Preview", "qwen3.6-max-preview"),
                ModelPreset("qwen36flash-us", "Qwen 3.6 Flash", "qwen3.6-flash"),
                ModelPreset("qwen35plus-us", "Qwen 3.5 Plus", "qwen3.5-plus"),
                ModelPreset("qwen35flash-us", "Qwen 3.5 Flash", "qwen3.5-flash")
            )
        }
        AiProvider.OPENAI -> listOf(
            ModelPreset("gpt55", "GPT 5.5", "gpt-5.5"),
            ModelPreset("gpt54", "GPT 5.4", "gpt-5.4"),
            ModelPreset("gpt54mini", "GPT 5.4 Mini", "gpt-5.4-mini", noteResId = R.string.model_note_recommended),
            ModelPreset("gpt5mini", "GPT 5 Mini", "gpt-5-mini")
        )
        AiProvider.GEMINI -> listOf(
            ModelPreset("gem31pro", "Gemini 3.1 Pro Preview", "gemini-3.1-pro-preview"),
            ModelPreset("gem25pro", "Gemini 2.5 Pro", "gemini-2.5-pro"),
            ModelPreset("gem31flash", "Gemini 3 Flash", "gemini-3-flash-preview", noteResId = R.string.model_note_recommended),
            ModelPreset("gem31flashlite", "Gemini 3.1 Flash Lite", "gemini-3.1-flash-lite-preview"),
            ModelPreset("gem25flash", "Gemini 2.5 Flash", "gemini-2.5-flash")
        )
        AiProvider.ANTHROPIC -> listOf(
            ModelPreset("claudeopus47", "Claude Opus 4.7", "claude-opus-4-7"),
            ModelPreset("claudeopus46", "Claude Opus 4.6", "claude-opus-4-6"),
            ModelPreset("claudesonnet46", "Claude Sonnet 4.6", "claude-sonnet-4-6", noteResId = R.string.model_note_recommended),
            ModelPreset("claudehaiku45", "Claude Haiku 4.5", "claude-haiku-4-5")
        )
        AiProvider.GLM -> listOf(
            ModelPreset("glm51", "GLM 5.1", "glm-5.1"),
            ModelPreset("glm5vturbo", "GLM 5V Turbo", "glm-5v-turbo", noteResId = R.string.model_note_recommended),
            ModelPreset("glm5", "GLM 5", "glm-5"),
            ModelPreset("glm46vflashx", "GLM 4.6V Flash X", "glm-4.6v-flashx"),
            ModelPreset("glm46v", "GLM 4.6V", "glm-4.6v")
        )
        AiProvider.CUSTOM -> emptyList()
    }
}

fun recommendedSttModelPresets(provider: AiProvider): List<ModelPreset> {
    return when (provider) {
        AiProvider.DASHSCOPE -> listOf(
            ModelPreset("das-fun-asr", "DashScope 实时 ASR", "fun-asr-realtime")
        )
        AiProvider.OPENAI -> listOf(
            ModelPreset("oa-4omini-transcribe", "GPT-4o Mini Transcribe", "gpt-4o-mini-transcribe"),
            ModelPreset("oa-4o-transcribe", "GPT-4o Transcribe", "gpt-4o-transcribe")
        )
        AiProvider.GEMINI -> listOf(
            ModelPreset("gem-fixed-tts", "Gemini 2.5 Flash Preview TTS", "gemini-2.5-flash-preview-tts")
        )
        AiProvider.GLM -> listOf(
            ModelPreset("glm-asr-2512", "GLM ASR 2512", "glm-asr-2512")
        )
        AiProvider.CUSTOM -> emptyList()
        AiProvider.ANTHROPIC -> emptyList()
    }
}
