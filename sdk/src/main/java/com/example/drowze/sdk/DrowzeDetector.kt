package com.example.drowze.sdk

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

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
        private const val MODEL_FILE_NAME = "face_landmarker.task"
        private const val DROWSY_THRESHOLD = 5000L
        private const val EAR_THRESHOLD = 0.2f
        private const val MAR_THRESHOLD = 0.5f
    }

    interface DetectionListener {
        fun onDrowsy(duration: Long)
        fun onAlert(message: String)
        fun onError(error: String)
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var listener: DetectionListener? = null
    private var isDetecting = false

    private var isDrowsy = false
    private var drowsyStartTime: Long = 0

    fun setListener(listener: DetectionListener) {
        this.listener = listener
    }

    fun initialize(modelPath: String? = null) {
        if (isDebuggerAttached()) {
            listener?.onError("Debugger detected")
            return
        }

        try {
            val modelPathToUse = modelPath ?: MODEL_FILE_NAME
            Log.d(TAG, "Initializing with model: $modelPathToUse")
            
            val baseOptionsBuilder = BaseOptions.builder()
            if (modelPathToUse.startsWith("/")) {
                baseOptionsBuilder.setModelPath(modelPathToUse)
            } else {
                baseOptionsBuilder.setModelAssetPath(modelPathToUse)
            }
            val baseOptions = baseOptionsBuilder.build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            isDetecting = true
            Log.d(TAG, "DrowzeDetector initialized with model: $modelPathToUse")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            listener?.onError("Failed to initialize detector: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap) {
        if (!isDetecting) return
        if (isDebuggerAttached()) return

        val landmarker = faceLandmarker ?: return

        try {
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
            val result = landmarker.detect(mpImage)
            processResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
        }
    }

    private fun processResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) {
            listener?.onAlert("No face detected")
            resetDrowsyState()
            return
        }

        val landmarks = result.faceLandmarks()[0]
        val ear = calculateEAR(landmarks)
        val mar = calculateMAR(landmarks)

        if (ear < EAR_THRESHOLD || mar > MAR_THRESHOLD) {
            if (!isDrowsy) {
                isDrowsy = true
                drowsyStartTime = System.currentTimeMillis()
                listener?.onAlert("Drowsiness detected")
            } else {
                val duration = System.currentTimeMillis() - drowsyStartTime
                if (duration >= DROWSY_THRESHOLD) {
                    listener?.onDrowsy(duration)
                }
            }
        } else {
            resetDrowsyState()
            listener?.onAlert("Alert - EAR: ${String.format("%.2f", ear)}")
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

    private fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }
}