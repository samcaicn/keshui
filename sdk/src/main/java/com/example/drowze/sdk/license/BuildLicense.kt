package com.example.drowze.sdk.license

import java.io.File

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
        return try {
            // 尝试从不同位置读取 .buildtime 文件
            val possiblePaths = listOf(
                ".buildtime",
                "${System.getProperty("user.dir")}/.buildtime",
                "${System.getProperty("user.dir")}/../.buildtime"
            )
            
            for (path in possiblePaths) {
                val buildTimeFile = File(path)
                if (buildTimeFile.exists()) {
                    return buildTimeFile.readText().trim().toLong()
                }
            }
            
            // 如果都找不到，返回当前时间
            System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}