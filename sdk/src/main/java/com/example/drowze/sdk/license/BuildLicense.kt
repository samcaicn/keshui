package com.example.drowze.sdk.license

object BuildLicense {
    const val LICENSE_KEY = "DROWZE-BUILD-TRIAL"
    const val IS_TRIAL = true
    const val VALIDITY_DAYS = 15
    const val CAN_RENEW = false

    fun isExpired(): Boolean {
        val buildTime = getBuildTime()
        val expiryTime = buildTime + (VALIDITY_DAYS * 24 * 60 * 60 * 1000L)
        return System.currentTimeMillis() > expiryTime
    }

    fun getRemainingDays(): Int {
        val buildTime = getBuildTime()
        val expiryTime = buildTime + (VALIDITY_DAYS * 24 * 60 * 60 * 1000L)
        val remaining = expiryTime - System.currentTimeMillis()
        return if (remaining > 0) (remaining / (24 * 60 * 60 * 1000L)).toInt() else 0
    }

    private fun getBuildTime(): Long {
        return 1714137600000L
    }
}