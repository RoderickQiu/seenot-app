package com.seenot.app.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SeenotAccountSession {
    private const val PREFS_NAME = "seenot_account_session"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_USER_STATUS = "user_status"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
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

    fun getAccessToken(): String = prefs?.getString(KEY_ACCESS_TOKEN, "")?.trim().orEmpty()

    fun getRefreshToken(): String = prefs?.getString(KEY_REFRESH_TOKEN, "")?.trim().orEmpty()

    fun getDeviceId(): String = prefs?.getString(KEY_DEVICE_ID, "")?.trim().orEmpty()

    fun hasSession(): Boolean = getAccessToken().isNotBlank() && getRefreshToken().isNotBlank()

    fun save(auth: SeenotAuthTokenResponse) {
        prefs?.edit()
            ?.putString(KEY_ACCESS_TOKEN, auth.accessToken)
            ?.putString(KEY_REFRESH_TOKEN, auth.refreshToken)
            ?.putString(KEY_USER_ID, auth.user.userId)
            ?.putString(KEY_USER_STATUS, auth.user.status)
            ?.putString(KEY_DEVICE_ID, auth.device?.deviceId.orEmpty())
            ?.apply()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}
