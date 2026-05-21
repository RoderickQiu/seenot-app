package com.seenot.app.account

import com.google.gson.annotations.SerializedName

data class SeenotAuthTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: Int,
    val user: SeenotUserResponse,
    val device: SeenotDeviceResponse?
)

data class SeenotUserResponse(
    @SerializedName("user_id") val userId: String,
    val status: String,
    @SerializedName("display_name") val displayName: String?,
    val locale: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class SeenotDeviceResponse(
    @SerializedName("device_id") val deviceId: String,
    val platform: String,
    @SerializedName("app_version") val appVersion: String?,
    @SerializedName("device_name") val deviceName: String?,
    @SerializedName("last_seen_at") val lastSeenAt: String,
    @SerializedName("revoked_at") val revokedAt: String?
)

data class SeenotDevicesResponse(
    val devices: List<SeenotDeviceResponse>
)

data class SeenotEntitlementResponse(
    val tier: String,
    val status: String,
    val provider: String?,
    @SerializedName("plan_code") val planCode: String?,
    @SerializedName("checkout_available") val checkoutAvailable: Boolean,
    @SerializedName("device_limit") val deviceLimit: Int,
    @SerializedName("linked_device_count") val linkedDeviceCount: Int,
    @SerializedName("fair_use_state") val fairUseState: String,
    @SerializedName("current_period_end") val currentPeriodEnd: String?,
    @SerializedName("manage_url") val manageUrl: String?
)

data class SeenotManagedAiSessionResponse(
    val provider: String,
    val region: String,
    @SerializedName("base_url") val baseUrl: String,
    val model: String,
    @SerializedName("api_key") val apiKey: String,
    @SerializedName("expires_at") val expiresAt: Long,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("fair_use_state") val fairUseState: String
)

data class SeenotAppLoginStartResponse(
    @SerializedName("request_id") val requestId: String,
    @SerializedName("login_url") val loginUrl: String,
    @SerializedName("expires_at") val expiresAt: String
)

data class SeenotAccountSnapshot(
    val auth: SeenotAuthTokenResponse,
    val entitlement: SeenotEntitlementResponse?
) {
    val isSignedIn: Boolean
        get() = auth.user.status == "active"

    val hasPlus: Boolean
        get() = entitlement?.tier == "plus" && entitlement.status == "active"
}

sealed interface SeenotAccountState {
    data object Loading : SeenotAccountState
    data object SignedOut : SeenotAccountState
    data class Ready(val snapshot: SeenotAccountSnapshot) : SeenotAccountState
    data class Error(val message: String) : SeenotAccountState
}
