package com.example.drowze.sdk.model

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
        private const val DEFAULT_TIMEOUT = 30000
        private const val BUFFER_SIZE = 8192
        private const val MAX_RETRY_COUNT = 3
        private const val PENDING_UPDATE_FILE = "pending_update.txt"
    }

    interface DownloadListener {
        fun onDownloadStart()
        fun onProgress(progress: Int)
        fun onSuccess(modelPath: String, isPending: Boolean)
        fun onError(error: String)
    }

    private var listener: DownloadListener? = null
    private var isDownloading = false
    private var retryCount = 0

    fun setListener(listener: DownloadListener) {
        this.listener = listener
    }

    fun downloadModel(
        modelUrl: String,
        version: String,
        checksum: String? = null,
        forceUpgrade: Boolean = false,
        minVersion: String? = null,
        applyOnRestart: Boolean = true
    ) {
        if (isDownloading) {
            listener?.onError("Download already in progress")
            return
        }

        if (!forceUpgrade) {
            val localPath = getLocalModelPath(version)
            if (localPath != null && verifyChecksum(File(localPath), checksum)) {
                Log.d(TAG, "Model file already exists and verified, skip download")
                listener?.onSuccess(localPath, false)
                return
            }
        }

        if (minVersion != null && isVersionCompatible(version, minVersion)) {
            Log.d(TAG, "Current version $version is below minimum $minVersion")
        }

        isDownloading = true
        retryCount = 0
        listener?.onDownloadStart()

        downloadWithRetry(modelUrl, version, checksum, applyOnRestart)
    }

    private fun downloadWithRetry(modelUrl: String, version: String, checksum: String?, applyOnRestart: Boolean) {
        Thread {
            try {
                val modelDir = File(context.filesDir, "models")
                if (!modelDir.exists()) {
                    modelDir.mkdirs()
                }

                val modelFile = File(modelDir, "face_landmarker_$version.task")

                if (modelFile.exists()) {
                    modelFile.delete()
                }

                val url = URL(modelUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = DEFAULT_TIMEOUT
                connection.readTimeout = DEFAULT_TIMEOUT
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned code: $responseCode")
                }

                val contentLength = connection.contentLength
                var downloadedSize = 0

                connection.inputStream.use { input ->
                    FileOutputStream(modelFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedSize += bytesRead

                            if (contentLength > 0) {
                                val progress = (downloadedSize * 100 / contentLength)
                                listener?.onProgress(progress)
                            }
                        }
                    }
                }

                connection.disconnect()

                if (checksum != null && !verifyChecksum(modelFile, checksum)) {
                    modelFile.delete()
                    throw Exception("Checksum verification failed")
                }

                if (applyOnRestart) {
                    markForUpdate(version)
                    listener?.onSuccess(modelFile.absolutePath, true)
                } else {
                    cleanupOldVersions(modelDir, version)
                    listener?.onSuccess(modelFile.absolutePath, false)
                }

                isDownloading = false
                retryCount = 0

            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                isDownloading = false

                if (retryCount < MAX_RETRY_COUNT) {
                    retryCount++
                    Log.d(TAG, "Retrying download ($retryCount/$MAX_RETRY_COUNT)")
                    downloadWithRetry(modelUrl, version, checksum, applyOnRestart)
                } else {
                    retryCount = 0
                    listener?.onError(e.message ?: "Download failed after $MAX_RETRY_COUNT retries")
                }
            }
        }.start()
    }

    fun checkForUpdate(serverUrl: String, currentVersion: String): Boolean {
        return try {
            val url = URL("$serverUrl/api/v1/model/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val latestVersion = extractVersionFromResponse(response)
                latestVersion != null && isNewerVersion(latestVersion, currentVersion)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check for update failed", e)
            false
        }
    }

    fun getPendingUpdate(): String? {
        val pendingFile = File(context.filesDir, PENDING_UPDATE_FILE)
        if (pendingFile.exists()) {
            try {
                return pendingFile.readText().trim()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read pending update file", e)
            }
        }
        return null
    }

    fun applyPendingUpdate(): String? {
        val pendingVersion = getPendingUpdate()
        if (pendingVersion != null) {
            val modelPath = getLocalModelPath(pendingVersion)
            if (modelPath != null) {
                cleanupOldVersions(File(context.filesDir, "models"), pendingVersion)
                clearPendingUpdate()
                return modelPath
            }
        }
        return null
    }

    fun clearPendingUpdate() {
        val pendingFile = File(context.filesDir, PENDING_UPDATE_FILE)
        if (pendingFile.exists()) {
            pendingFile.delete()
        }
    }

    private fun markForUpdate(version: String) {
        val pendingFile = File(context.filesDir, PENDING_UPDATE_FILE)
        try {
            pendingFile.writeText(version)
            Log.d(TAG, "Marked version $version for update on next restart")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to mark for update", e)
        }
    }

    private fun extractVersionFromResponse(response: String): String? {
        return try {
            val index = response.indexOf("\"version\"")
            if (index != -1) {
                val startIndex = response.indexOf('"', index + 9)
                if (startIndex != -1) {
                    val endIndex = response.indexOf('"', startIndex + 1)
                    if (endIndex != -1) {
                        return response.substring(startIndex + 1, endIndex)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract version", e)
            null
        }
    }

    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        val newParts = newVersion.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until Math.max(newParts.size, currentParts.size)) {
            val new = newParts.getOrElse(i) { 0 }
            val current = currentParts.getOrElse(i) { 0 }
            if (new > current) return true
            if (new < current) return false
        }
        return false
    }

    private fun isVersionCompatible(version: String, minVersion: String): Boolean {
        return !isNewerVersion(version, minVersion)
    }

    fun getLocalModelPath(version: String): String? {
        val modelFile = File(context.filesDir, "models/face_landmarker_$version.task")
        return if (modelFile.exists()) modelFile.absolutePath else null
    }

    fun getLatestLocalVersion(): String? {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) return null

        return modelDir.listFiles()
            ?.filter { it.name.startsWith("face_landmarker_") && it.name.endsWith(".task") }
            ?.map { it.name.removePrefix("face_landmarker_").removeSuffix(".task") }
            ?.maxByOrNull { it }
    }

    fun deleteModel(version: String): Boolean {
        val modelFile = File(context.filesDir, "models/face_landmarker_$version.task")
        return modelFile.delete()
    }

    fun getCachedModels(): List<String> {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) return emptyList()

        return modelDir.listFiles()
            ?.filter { it.name.startsWith("face_landmarker_") && it.name.endsWith(".task") }
            ?.map { it.name.removePrefix("face_landmarker_").removeSuffix(".task") }
            ?: emptyList()
    }

    fun cancelDownload() {
        isDownloading = false
        retryCount = 0
    }

    private fun verifyChecksum(file: File, expectedChecksum: String?): Boolean {
        if (expectedChecksum == null) return file.exists()

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val actualChecksum = digest.digest().joinToString("") { "%02x".format(it) }
            actualChecksum.equals(expectedChecksum, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Checksum verification error", e)
            false
        }
    }

    private fun cleanupOldVersions(modelDir: File, keepVersion: String) {
        modelDir.listFiles()
            ?.filter { it.name.startsWith("face_landmarker_") && it.name.endsWith(".task") }
            ?.filter { !it.name.contains(keepVersion) }
            ?.forEach { it.delete() }
    }
}