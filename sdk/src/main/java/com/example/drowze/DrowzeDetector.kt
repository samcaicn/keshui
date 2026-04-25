package com.example.drowze

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.example.drowze.license.BuildLicense
import com.example.drowze.model.ModelDownloader
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.pow
import kotlin.math.sqrt

class DrowzeDetector(private val context: Context) {
    companion object {
        private const val TAG = "DrowzeDetector"
        private val ENCRYPTION_KEY = "drowze_sdk_key_2024".toCharArray()

        private const val DEFAULT_MODEL_VERSION = "1.0.0"
        private const val MODEL_FILE_NAME = "face_landmarker.task"
    }

    interface DetectionListener {
        fun onDrowsy(duration: Long)
        fun onAlert(message: String)
        fun onError(error: String)
        fun onTrialWarning(daysRemaining: Int)
        fun onModelDownloadStart()
        fun onModelDownloadProgress(progress: Int)
        fun onModelDownloadSuccess(isPending: Boolean)
        fun onModelDownloadError(error: String)
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var listener: DetectionListener? = null
    private var isDetecting = false

    private var isDrowsy = false
    private var drowsyStartTime: Long = 0
    private val drowsyThreshold = 5000L

    private val modelDownloader = ModelDownloader(context)

    private var serverUrl: String = ""
    private var currentModelVersion: String = DEFAULT_MODEL_VERSION
    private var currentChecksum: String = ""

    fun setListener(listener: DetectionListener) {
        this.listener = listener
    }

    fun setServerConfig(serverUrl: String, modelVersion: String, checksum: String) {
        this.serverUrl = serverUrl
        this.currentModelVersion = modelVersion
        this.currentChecksum = checksum
    }

    fun initialize(modelPath: String? = null) {
        if (isDebuggerAttached()) {
            listener?.onError(decryptString(encryptString("Debugger detected")))
            return
        }

        if (BuildLicense.IS_TRIAL) {
            if (BuildLicense.isExpired()) {
                listener?.onError(decryptString(encryptString("Trial license expired")))
                return
            }
            val remainingDays = BuildLicense.getRemainingDays()
            if (remainingDays <= 7) {
                listener?.onTrialWarning(remainingDays)
            }
        }

        val pendingUpdate = modelDownloader.getPendingUpdate()
        if (pendingUpdate != null) {
            Log.d(TAG, "Applying pending update: $pendingUpdate")
            val pendingPath = modelDownloader.applyPendingUpdate()
            if (pendingPath != null) {
                Log.d(TAG, "Applied pending update, using new model")
                initializeWithModel(pendingPath)
                return
            }
        }

        val localModelPath = modelPath ?: findBestModelPath()

        if (localModelPath != null) {
            initializeWithModel(localModelPath)
        } else if (serverUrl.isNotEmpty()) {
            downloadAndInitialize()
        } else {
            val defaultPath = decryptString(encryptString(MODEL_FILE_NAME))
            initializeWithModel(defaultPath)
        }
    }

    fun checkAndUpdate() {
        if (serverUrl.isNotEmpty()) {
            val hasUpdate = modelDownloader.checkForUpdate(serverUrl, currentModelVersion)
            if (hasUpdate) {
                downloadModelForRestart()
            }
        }
    }

    private fun downloadModelForRestart() {
        modelDownloader.setListener(object : ModelDownloader.DownloadListener {
            override fun onDownloadStart() {
                listener?.onModelDownloadStart()
            }

            override fun onProgress(progress: Int) {
                listener?.onModelDownloadProgress(progress)
            }

            override fun onSuccess(modelPath: String, isPending: Boolean) {
                if (isPending) {
                    listener?.onModelDownloadSuccess(true)
                } else {
                    listener?.onModelDownloadSuccess(false)
                }
            }

            override fun onError(error: String) {
                listener?.onModelDownloadError(error)
            }
        })

        val modelUrl = "$serverUrl/models/face_landmarker_$currentModelVersion.task"
        modelDownloader.downloadModel(
            modelUrl,
            currentModelVersion,
            currentChecksum,
            applyOnRestart = true
        )
    }

    private fun findBestModelPath(): String? {
        val cachedModels = modelDownloader.getCachedModels()
        if (cachedModels.isNotEmpty()) {
            val latestVersion = cachedModels.maxByOrNull { it } ?: return null
            return modelDownloader.getLocalModelPath(latestVersion)
        }

        val localPath = modelDownloader.getLocalModelPath(DEFAULT_MODEL_VERSION)
        return localPath
    }

    private fun downloadAndInitialize() {
        modelDownloader.setListener(object : ModelDownloader.DownloadListener {
            override fun onDownloadStart() {
                listener?.onModelDownloadStart()
            }

            override fun onProgress(progress: Int) {
                listener?.onModelDownloadProgress(progress)
            }

            override fun onSuccess(modelPath: String, isPending: Boolean) {
                if (!isPending) {
                    listener?.onModelDownloadSuccess(false)
                    initializeWithModel(modelPath)
                }
            }

            override fun onError(error: String) {
                listener?.onModelDownloadError(error)
                val defaultPath = decryptString(encryptString(MODEL_FILE_NAME))
                initializeWithModel(defaultPath)
            }
        })

        val modelUrl = "$serverUrl/models/face_landmarker_$currentModelVersion.task"
        modelDownloader.downloadModel(
            modelUrl,
            currentModelVersion,
            currentChecksum,
            applyOnRestart = false
        )
    }

    private fun initializeWithModel(modelPath: String) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelPath)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            isDetecting = true
            Log.d(TAG, decryptString(encryptString("DrowzeDetector initialized with model: $modelPath")))
        } catch (e: Exception) {
            Log.e(TAG, decryptString(encryptString("Failed to initialize")), e)
            listener?.onError(decryptString(encryptString("Failed to initialize detector")))
        }
    }

    fun detect(bitmap: Bitmap) {
        if (!isDetecting) return
        if (isDebuggerAttached()) return

        if (BuildLicense.IS_TRIAL && BuildLicense.isExpired()) {
            listener?.onError(decryptString(encryptString("Trial license expired")))
            return
        }

        val landmarker = faceLandmarker ?: return

        try {
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)
            processResult(result)
        } catch (e: Exception) {
            Log.e(TAG, decryptString(encryptString("Detection failed")), e)
        }
    }

    private fun processResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) {
            listener?.onAlert(decryptString(encryptString("No face detected")))
            resetDrowsyState()
            return
        }

        val landmarks = result.faceLandmarks()[0]
        val ear = calculateEAR(landmarks)
        val mar = calculateMAR(landmarks)

        if (ear < 0.2f || mar > 0.5f) {
            if (!isDrowsy) {
                isDrowsy = true
                drowsyStartTime = System.currentTimeMillis()
                listener?.onAlert(decryptString(encryptString("Drowsiness detected")))
            } else {
                val duration = System.currentTimeMillis() - drowsyStartTime
                if (duration >= drowsyThreshold) {
                    listener?.onDrowsy(duration)
                }
            }
        } else {
            resetDrowsyState()
            listener?.onAlert(decryptString(encryptString("Alert - EAR: ${String.format("%.2f", ear)}")))
        }
    }

    private fun calculateEAR(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        val p1 = landmarks[33]
        val p2 = landmarks[159]
        val p3 = landmarks[160]
        val p4 = landmarks[133]
        val p5 = landmarks[144]
        val p6 = landmarks[145]

        val vert1 = distance(p2, p6)
        val vert2 = distance(p3, p5)
        val horiz = distance(p1, p4)

        return (vert1 + vert2) / (2.0f * horiz)
    }

    private fun calculateMAR(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Float {
        val p1 = landmarks[13]
        val p2 = landmarks[14]
        val p3 = landmarks[78]
        val p4 = landmarks[308]
        val p5 = landmarks[61]
        val p6 = landmarks[291]

        val vert1 = distance(p1, p2)
        val vert2 = distance(p3, p4)
        val horiz = distance(p5, p6)

        return (vert1 + vert2) / (2.0f * horiz)
    }

    private fun distance(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float {
        return sqrt((p1.x() - p2.x()).pow(2) + (p1.y() - p2.y()).pow(2))
    }

    private fun resetDrowsyState() {
        isDrowsy = false
    }

    fun vibrate(pattern: LongArray = longArrayOf(0, 500, 500)) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(1000)
        }
    }

    fun stopVibration() {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.cancel()
    }

    fun release() {
        isDetecting = false
        faceLandmarker?.close()
        faceLandmarker = null
        listener = null
    }

    fun isTrialLicense(): Boolean = BuildLicense.IS_TRIAL

    fun getTrialRemainingDays(): Int = BuildLicense.getRemainingDays()

    fun canRenewTrial(): Boolean = BuildLicense.CAN_RENEW

    fun getCurrentModelVersion(): String = currentModelVersion

    fun getCachedModelVersions(): List<String> = modelDownloader.getCachedModels()

    fun deleteCachedModel(version: String): Boolean = modelDownloader.deleteModel(version)

    fun cancelModelDownload() = modelDownloader.cancelDownload()

    private fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    private fun encryptString(input: String): String {
        val chars = input.toCharArray()
        for (i in chars.indices) {
            chars[i] = (chars[i].code xor ENCRYPTION_KEY[i % ENCRYPTION_KEY.size].code).toChar()
        }
        return String(chars)
    }

    private fun decryptString(input: String): String {
        val chars = input.toCharArray()
        for (i in chars.indices) {
            chars[i] = (chars[i].code xor ENCRYPTION_KEY[i % ENCRYPTION_KEY.size].code).toChar()
        }
        return String(chars)
    }
}