package com.example.drowze.license

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import com.google.gson.Gson
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class LicenseManager private constructor(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "drowze_license"
        private const val KEY_LICENSE_TOKEN = "license_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_VERIFY = "last_verify"
        private const val KEY_OFFLINE_COUNT = "offline_count"
        private const val OFFLINE_VALIDITY_DAYS = 7
        private const val MAX_OFFLINE_DAYS = 30
    }

    interface LicenseListener {
        fun onLicenseValid()
        fun onLicenseInvalid(reason: String)
        fun onLicenseExpired()
        fun onLicenseExceeded()
        fun onNetworkError(error: String)
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private var listener: LicenseListener? = null

    private var apiKey: String = ""
    private var apiSecret: String = ""
    private var serverUrl: String = ""

    fun configure(apiKey: String, apiSecret: String, serverUrl: String) {
        this.apiKey = encryptString(apiKey)
        this.apiSecret = encryptString(apiSecret)
        this.serverUrl = encryptString(serverUrl)
    }

    fun setListener(listener: LicenseListener) {
        this.listener = listener
    }

    fun activate(licenseKey: String, deviceId: String? = null) {
        val actualDeviceId = deviceId ?: generateDeviceId()
        val hashedDeviceId = hashDeviceId(actualDeviceId)

        val token = generateToken(licenseKey, hashedDeviceId)

        prefs.edit().apply {
            putString(KEY_LICENSE_TOKEN, encryptString(token))
            putString(KEY_DEVICE_ID, encryptString(actualDeviceId))
            putLong(KEY_LAST_VERIFY, System.currentTimeMillis())
            putInt(KEY_OFFLINE_COUNT, 0)
            apply()
        }

        verifyOnline(licenseKey, actualDeviceId)
    }

    fun verify(): Boolean {
        val token = decryptString(prefs.getString(KEY_LICENSE_TOKEN, "") ?: "")
        val lastVerify = prefs.getLong(KEY_LAST_VERIFY, 0)
        val offlineCount = prefs.getInt(KEY_OFFLINE_COUNT, 0)

        if (token.isEmpty()) {
            listener?.onLicenseInvalid("License not activated")
            return false
        }

        val daysSinceVerify = (System.currentTimeMillis() - lastVerify) / (1000 * 60 * 60 * 24)

        if (daysSinceVerify > MAX_OFFLINE_DAYS) {
            listener?.onLicenseExpired()
            return false
        }

        if (daysSinceVerify > OFFLINE_VALIDITY_DAYS) {
            if (offlineCount >= 3) {
                listener?.onLicenseExceeded()
                return false
            }
        }

        return true
    }

    fun verifyOnline(licenseKey: String, deviceId: String? = null) {
        Thread {
            try {
                val actualDeviceId = deviceId ?: decryptString(prefs.getString(KEY_DEVICE_ID, "") ?: "")
                if (actualDeviceId.isEmpty()) {
                    listener?.onNetworkError("Device not registered")
                    return@Thread
                }

                val isValid = validateWithServer(licenseKey, actualDeviceId)

                if (isValid) {
                    prefs.edit().putLong(KEY_LAST_VERIFY, System.currentTimeMillis()).apply()
                    prefs.edit().putInt(KEY_OFFLINE_COUNT, 0).apply()
                    listener?.onLicenseValid()
                } else {
                    incrementOfflineCount()
                    listener?.onLicenseInvalid("License validation failed")
                }
            } catch (e: Exception) {
                incrementOfflineCount()
                listener?.onNetworkError(e.message ?: "Network error")
            }
        }.start()
    }

    fun deactivate() {
        prefs.edit().clear().apply()
    }

    fun isActivated(): Boolean {
        val token = decryptString(prefs.getString(KEY_LICENSE_TOKEN, "") ?: "")
        return token.isNotEmpty()
    }

    fun getDeviceId(): String {
        return decryptString(prefs.getString(KEY_DEVICE_ID, "") ?: "")
    }

    fun getRemainingOfflineDays(): Int {
        val lastVerify = prefs.getLong(KEY_LAST_VERIFY, 0)
        val daysSinceVerify = (System.currentTimeMillis() - lastVerify) / (1000 * 60 * 60 * 24)
        return maxOf(0, (MAX_OFFLINE_DAYS - daysSinceVerify).toInt())
    }

    private fun validateWithServer(licenseKey: String, deviceId: String): Boolean {
        val hashedDeviceId = hashDeviceId(deviceId)
        val expectedToken = generateToken(licenseKey, hashedDeviceId)
        val storedToken = decryptString(prefs.getString(KEY_LICENSE_TOKEN, "") ?: "")

        return expectedToken == storedToken
    }

    private fun generateDeviceId(): String {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: ""

        val deviceInfo = "${Build.MANUFACTURER}_${Build.MODEL}_${Build.SERIAL}_$androidId".trim()

        return hashString(deviceInfo + getRandomSalt())
    }

    private fun hashDeviceId(deviceId: String): String {
        return hashString(deviceId + decryptString(apiSecret))
    }

    private fun generateToken(licenseKey: String, hashedDeviceId: String): String {
        val data = "$licenseKey:$hashedDeviceId:${System.currentTimeMillis() / (1000 * 60 * 60)}"
        return hashString(data)
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getRandomSalt(): String {
        val saltFile = context.getFileStreamPath(".salt")
        return if (saltFile.exists()) {
            saltFile.readText()
        } else {
            val salt = UUID.randomUUID().toString()
            saltFile.writeText(salt)
            salt
        }
    }

    private fun incrementOfflineCount() {
        val current = prefs.getInt(KEY_OFFLINE_COUNT, 0)
        prefs.edit().putInt(KEY_OFFLINE_COUNT, current + 1).apply()
    }

    private fun encryptString(input: String): String {
        if (input.isEmpty()) return ""
        val chars = input.toCharArray()
        val key = "drowze_sdk_license_2024".toCharArray()
        for (i in chars.indices) {
            chars[i] = (chars[i].code xor key[i % key.size].code).toChar()
        }
        return Base64.encodeToString(String(chars).toByteArray(), Base64.NO_WRAP)
    }

    private fun decryptString(input: String): String {
        if (input.isEmpty()) return ""
        return try {
            val decoded = Base64.decode(input, Base64.NO_WRAP)
            val chars = String(decoded).toCharArray()
            val key = "drowze_sdk_license_2024".toCharArray()
            for (i in chars.indices) {
                chars[i] = (chars[i].code xor key[i % key.size].code).toChar()
            }
            String(chars)
        } catch (e: Exception) {
            ""
        }
    }
}

object LicenseManagerFactory {
    private var instance: LicenseManager? = null

    fun getInstance(context: Context): LicenseManager {
        if (instance == null) {
            synchronized(this) {
                if (instance == null) {
                    instance = LicenseManager(context.applicationContext)
                }
            }
        }
        return instance!!
    }
}