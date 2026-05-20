package com.seenot.app.account

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.seenot.app.BuildConfig
import com.seenot.app.config.AppLocalePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class SeenotAccountApi(
    private val context: Context,
    private val baseUrl: String = BuildConfig.SEENOT_BACKEND_API_BASE_URL
) {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun loadAccount(): SeenotAccountState = withContext(Dispatchers.IO) {
        SeenotAccountSession.init(appContext)
        if (!SeenotAccountSession.hasSession()) {
            return@withContext SeenotAccountState.SignedOut
        }

        runCatching {
            val auth = refresh()
            val entitlement = getEntitlement(auth.accessToken)
            SeenotAccountState.Ready(SeenotAccountSnapshot(auth = auth, entitlement = entitlement))
        }.getOrElse { error ->
            if (error is SeenotAuthException) {
                SeenotAccountSession.clear()
                SeenotAccountState.SignedOut
            } else {
                SeenotAccountState.Error(error.message ?: "Could not refresh account.")
            }
        }
    }

    suspend fun createLocalDeviceSession(): SeenotAccountState = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("platform", "android")
                addProperty("app_version", BuildConfig.VERSION_NAME)
                addProperty("device_name", buildDeviceName())
                addProperty("locale", AppLocalePrefs.getLanguage(appContext))
            }
            val auth = postJson(
                path = "/auth/device",
                body = body,
                token = SeenotAccountSession.getAccessToken().ifBlank { null },
                responseClass = SeenotAuthTokenResponse::class.java
            )
            SeenotAccountSession.save(auth)
            val entitlement = getEntitlement(auth.accessToken)
            SeenotAccountState.Ready(SeenotAccountSnapshot(auth = auth, entitlement = entitlement))
        }.getOrElse { error ->
            SeenotAccountState.Error(error.message ?: "Could not connect this device.")
        }
    }

    suspend fun createManagedAiSession(): SeenotManagedAiSessionResponse = withContext(Dispatchers.IO) {
        postJson(
            path = "/managed-ai/session",
            body = JsonObject(),
            token = SeenotAccountSession.getAccessToken(),
            responseClass = SeenotManagedAiSessionResponse::class.java
        )
    }

    private fun refresh(): SeenotAuthTokenResponse {
        val refreshToken = SeenotAccountSession.getRefreshToken()
        if (refreshToken.isBlank()) throw SeenotAuthException("Missing refresh token.")
        val body = JsonObject().apply {
            addProperty("refresh_token", refreshToken)
        }
        return postJson(
            path = "/auth/refresh",
            body = body,
            token = null,
            responseClass = SeenotAuthTokenResponse::class.java
        ).also(SeenotAccountSession::save)
    }

    private fun getEntitlement(token: String): SeenotEntitlementResponse {
        return getJson(
            path = "/entitlement",
            token = token,
            responseClass = SeenotEntitlementResponse::class.java
        )
    }

    private fun <T> getJson(path: String, token: String, responseClass: Class<T>): T {
        val request = Request.Builder()
            .url(apiUrl(path))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return execute(request, responseClass)
    }

    private fun <T> postJson(path: String, body: JsonObject, token: String?, responseClass: Class<T>): T {
        val builder = Request.Builder()
            .url(apiUrl(path))
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return execute(builder.build(), responseClass)
    }

    private fun <T> execute(request: Request, responseClass: Class<T>): T {
        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    throw SeenotAuthException(extractErrorMessage(responseText) ?: "Please sign in again.")
                }
                throw IOException(extractErrorMessage(responseText) ?: "HTTP ${response.code}")
            }
            return gson.fromJson(responseText, responseClass)
        }
    }

    private fun apiUrl(path: String): String {
        return "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
    }

    private fun extractErrorMessage(responseText: String): String? {
        return runCatching {
            val root = gson.fromJson(responseText, JsonObject::class.java)
            val detail = root.get("detail")
            when {
                detail == null || detail.isJsonNull -> null
                detail.isJsonPrimitive -> detail.asString
                detail.isJsonObject -> detail.asJsonObject.get("message")?.asString
                else -> detail.toString()
            }
        }.getOrNull()
    }

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android device" }
    }
}

private class SeenotAuthException(message: String) : RuntimeException(message)
