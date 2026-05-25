package com.seenot.app.account

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.seenot.app.BuildConfig
import com.seenot.app.config.AppLocalePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

open class SeenotAccountApi(
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
            ensureCurrentInstallationIfMissing(auth.accessToken, auth.device?.deviceId)
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
                addProperty("installation_id", SeenotAccountSession.getInstallationId())
                SeenotAccountSession.getDeviceId().ifBlank { null }?.let { addProperty("device_id", it) }
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

    suspend fun startAppLogin(redirectUri: String): SeenotAppLoginStartResponse = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("platform", "android")
            addProperty("app_version", BuildConfig.VERSION_NAME)
            addProperty("device_name", buildDeviceName())
            addProperty("locale", AppLocalePrefs.getLanguage(appContext))
            addProperty("installation_id", SeenotAccountSession.getInstallationId())
            addProperty("redirect_uri", redirectUri)
        }
        postJson(
            path = "/auth/app-login/start",
            body = body,
            token = null,
            responseClass = SeenotAppLoginStartResponse::class.java
        )
    }

    suspend fun exchangeAppLogin(requestId: String, exchangeCode: String): SeenotAccountState = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("request_id", requestId)
                addProperty("exchange_code", exchangeCode)
            }
            val auth = postJson(
                path = "/auth/app-login/exchange",
                body = body,
                token = null,
                responseClass = SeenotAuthTokenResponse::class.java
            )
            SeenotAccountSession.save(auth)
            ensureDevice(auth.accessToken)
            val entitlement = getEntitlement(auth.accessToken)
            SeenotAccountState.Ready(SeenotAccountSnapshot(auth = auth, entitlement = entitlement))
        }.getOrElse { error ->
            if (error is SeenotAuthException) {
                SeenotAccountSession.clear()
                SeenotAccountState.SignedOut
            } else {
                SeenotAccountState.Error(error.message ?: "Could not complete app login.")
            }
        }
    }

    open suspend fun createManagedAiSession(): SeenotManagedAiSessionResponse = withContext(Dispatchers.IO) {
        retryOnceOnTransientClose {
            createManagedAiSessionWithFreshAccessToken()
        }
    }

    suspend fun readSyncChanges(since: Long, limit: Int = 200): SyncChangesResponse = withContext(Dispatchers.IO) {
        retryOnceOnTransientClose {
            readSyncChangesWithFreshAccessToken(since, limit)
        }
    }

    suspend fun readSyncEntities(
        entityType: String? = null,
        entityIds: List<String> = emptyList(),
        includeDeleted: Boolean = true
    ): SyncEntitySnapshotResponse = withContext(Dispatchers.IO) {
        retryOnceOnTransientClose {
            readSyncEntitiesWithFreshAccessToken(entityType, entityIds, includeDeleted)
        }
    }

    suspend fun writeSyncOps(ops: List<SyncOpRequest>): SyncOpsResponse = withContext(Dispatchers.IO) {
        retryOnceOnTransientClose {
            writeSyncOpsWithFreshAccessToken(ops)
        }
    }

    suspend fun revokeCurrentDevice() = withContext(Dispatchers.IO) {
        val deviceId = SeenotAccountSession.getDeviceId()
        val accessToken = SeenotAccountSession.getAccessToken()
        if (deviceId.isBlank() || accessToken.isBlank()) return@withContext

        runCatching {
            postJson(
                path = "/devices/$deviceId/revoke",
                body = JsonObject(),
                token = accessToken,
                responseClass = SeenotRevokeDeviceResponse::class.java
            )
        }.recoverCatching { error ->
            if (error is SeenotAuthException) {
                val refreshed = refresh()
                postJson(
                    path = "/devices/$deviceId/revoke",
                    body = JsonObject(),
                    token = refreshed.accessToken,
                    responseClass = SeenotRevokeDeviceResponse::class.java
                )
            } else {
                throw error
            }
        }
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

    private fun createManagedAiSessionWithFreshAccessToken(): SeenotManagedAiSessionResponse {
        return try {
            postManagedAiSession(SeenotAccountSession.getAccessToken())
        } catch (error: SeenotAuthException) {
            val refreshed = refresh()
            postManagedAiSession(refreshed.accessToken)
        }
    }

    private fun readSyncChangesWithFreshAccessToken(since: Long, limit: Int): SyncChangesResponse {
        return try {
            getSyncChanges(SeenotAccountSession.getAccessToken(), since, limit)
        } catch (error: SeenotAuthException) {
            val refreshed = refresh()
            getSyncChanges(refreshed.accessToken, since, limit)
        }
    }

    private fun readSyncEntitiesWithFreshAccessToken(
        entityType: String?,
        entityIds: List<String>,
        includeDeleted: Boolean
    ): SyncEntitySnapshotResponse {
        return try {
            getSyncEntities(SeenotAccountSession.getAccessToken(), entityType, entityIds, includeDeleted)
        } catch (error: SeenotAuthException) {
            val refreshed = refresh()
            getSyncEntities(refreshed.accessToken, entityType, entityIds, includeDeleted)
        }
    }

    private fun writeSyncOpsWithFreshAccessToken(ops: List<SyncOpRequest>): SyncOpsResponse {
        return try {
            postSyncOps(SeenotAccountSession.getAccessToken(), ops)
        } catch (error: SeenotAuthException) {
            val refreshed = refresh()
            postSyncOps(refreshed.accessToken, ops)
        }
    }

    private fun getEntitlement(token: String): SeenotEntitlementResponse {
        return getJson(
            path = "/entitlement",
            token = token,
            responseClass = SeenotEntitlementResponse::class.java
        )
    }

    private fun listDevices(token: String): SeenotDevicesResponse {
        return getJson(
            path = "/devices",
            token = token,
            responseClass = SeenotDevicesResponse::class.java
        )
    }

    private fun ensureCurrentInstallationIfMissing(token: String, currentDeviceId: String?) {
        val devices = listDevices(token).devices
        val targetDeviceId = currentDeviceId ?: SeenotAccountSession.getDeviceId()
        val exists = targetDeviceId.isNotBlank() && devices.any { it.deviceId == targetDeviceId }
        if (!exists) {
            ensureDevice(token)
        }
    }

    private fun ensureDevice(token: String): SeenotDeviceResponse {
        val body = JsonObject().apply {
            addProperty("platform", "android")
            addProperty("app_version", BuildConfig.VERSION_NAME)
            addProperty("device_name", buildDeviceName())
            addProperty("locale", AppLocalePrefs.getLanguage(appContext))
            addProperty("installation_id", SeenotAccountSession.getInstallationId())
            SeenotAccountSession.getDeviceId().ifBlank { null }?.let { addProperty("device_id", it) }
        }
        val device = postJson(
            path = "/auth/device/ensure",
            body = body,
            token = token,
            responseClass = SeenotDeviceResponse::class.java
        )
        SeenotAccountSession.saveDeviceId(device.deviceId)
        return device
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

    private fun postManagedAiSession(token: String): SeenotManagedAiSessionResponse {
        return postJson(
            path = "/managed-ai/session",
            body = JsonObject(),
            token = token,
            responseClass = SeenotManagedAiSessionResponse::class.java
        )
    }

    private fun getSyncChanges(token: String, since: Long, limit: Int): SyncChangesResponse {
        return getJson(
            path = "/sync/changes?since=${since.coerceAtLeast(0L)}&limit=${limit.coerceIn(1, 500)}",
            token = token,
            responseClass = SyncChangesResponse::class.java
        )
    }

    private fun getSyncEntities(
        token: String,
        entityType: String?,
        entityIds: List<String>,
        includeDeleted: Boolean
    ): SyncEntitySnapshotResponse {
        val params = mutableListOf("include_deleted=$includeDeleted")
        entityType?.takeIf { it.isNotBlank() }?.let { params += "entity_type=$it" }
        if (entityIds.isNotEmpty()) params += "entity_ids=${entityIds.joinToString(",")}"
        return getJson(
            path = "/sync/entities?${params.joinToString("&")}",
            token = token,
            responseClass = SyncEntitySnapshotResponse::class.java
        )
    }

    private fun postSyncOps(token: String, ops: List<SyncOpRequest>): SyncOpsResponse {
        val body = gson.toJsonTree(SyncOpsRequest(ops = ops)).asJsonObject
        return postJson(
            path = "/sync/ops",
            body = body,
            token = token,
            responseClass = SyncOpsResponse::class.java
        )
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

    private suspend fun <T> retryOnceOnTransientClose(block: () -> T): T {
        return try {
            block()
        } catch (error: IOException) {
            if (!error.isTransientConnectionClose()) throw error
            delay(250)
            block()
        }
    }

    private fun IOException.isTransientConnectionClose(): Boolean {
        val normalized = message?.lowercase().orEmpty()
        return "connection closed" in normalized || "unexpected end of stream" in normalized
    }
}

private class SeenotAuthException(message: String) : RuntimeException(message)
