package com.example.drowze.sdk.license

import com.google.gson.annotations.SerializedName

data class ActivateRequest(
    @SerializedName("license_key")
    val licenseKey: String,

    @SerializedName("device_id")
    val deviceId: String,

    @SerializedName("device_fingerprint")
    val deviceFingerprint: String,

    @SerializedName("package_name")
    val packageName: String,

    @SerializedName("timestamp")
    val timestamp: Long
)

data class ActivateResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String?,

    @SerializedName("expires_at")
    val expiresAt: Long?,

    @SerializedName("max_devices")
    val maxDevices: Int?,

    @SerializedName("current_devices")
    val currentDevices: Int?
)

data class VerifyRequest(
    @SerializedName("license_key")
    val licenseKey: String,

    @SerializedName("device_id")
    val deviceId: String,

    @SerializedName("token")
    val token: String,

    @SerializedName("timestamp")
    val timestamp: Long
)

data class VerifyResponse(
    @SerializedName("valid")
    val valid: Boolean,

    @SerializedName("message")
    val message: String?,

    @SerializedName("remaining_offline_days")
    val remainingOfflineDays: Int?
)

enum class LicenseError(val message: String) {
    INVALID_LICENSE_KEY("Invalid license key"),
    DEVICE_NOT_REGISTERED("Device not registered"),
    LICENSE_EXPIRED("License has expired"),
    DEVICE_LIMIT_EXCEEDED("Device limit exceeded"),
    SERVER_ERROR("Server error"),
    NETWORK_ERROR("Network error"),
    INVALID_CONFIGURATION("Invalid SDK configuration")
}