package com.example.drowze

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA
        )

        private const val EAR_THRESHOLD = 0.21f
        private const val DROWSY_TIME_THRESHOLD = 5000L
        private const val EYES_CLOSED_TIME_THRESHOLD = 3000L
        private const val MAR_THRESHOLD = 0.65f
        private const val YAWN_TIME_THRESHOLD = 1500L
        private const val CONSECUTIVE_FRAMES_THRESHOLD = 5
        private const val EAR_HISTORY_SIZE = 5
        private const val MIN_VALID_EAR = 0.05f
        private const val MAX_VALID_EAR = 1.0f
        private const val ALARM_RELEASE_DELAY = 3000L
    }

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    private var faceLandmarker: FaceLandmarker? = null

    private var isDrowsy = false
    private var drowsyStartTime: Long = 0
    private var consecutiveEyesClosedFrames = 0
    private val earHistory = mutableListOf<Float>()
    private var eyeOpenStartTime: Long = 0
    private var isAlarmActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        statusText = findViewById(R.id.status_text)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        } else {
            setupFaceLandmarker()
            startCamera()
        }
    }

    private fun setupFaceLandmarker() {
        try {
            Log.d(TAG, "Setting up FaceLandmarker")
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
            Log.d(TAG, "FaceLandmarker initialized successfully")
            updateStatus("Ready - Eye Open", 0f, 0f)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up FaceLandmarker", e)
            Toast.makeText(this, "Failed to setup face detection", Toast.LENGTH_LONG).show()
            updateStatus("Failed to setup face detection: ${e.message}", 0f, 0f)
        }
    }

    private fun startCamera() {
        Log.d(TAG, "Starting camera")
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "Camera provider obtained")

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview
                )
                Log.d(TAG, "Camera bound to lifecycle")

                setupFrameCapture()
                updateStatus("Camera started - Eye Open", 0f, 0f)
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                updateStatus("Failed to start camera: ${e.message}", 0f, 0f)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupFrameCapture() {
        Log.d(TAG, "Setting up frame capture")
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val handler = android.os.Handler()
        val frameCapture = object : Runnable {
            override fun run() {
                captureCameraFrame()
                handler.postDelayed(this, 200)
            }
        }

        handler.post(frameCapture)
        Log.d(TAG, "Frame capture setup completed")
    }

    private fun captureCameraFrame() {
        val bitmap = previewView.bitmap ?: return
        Log.d(TAG, "Captured camera frame")

        val rotatedBitmap = rotateBitmap(bitmap, 90f)

        processFrame(rotatedBitmap)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun processFrame(bitmap: Bitmap) {
        val faceLandmarker = faceLandmarker ?: return
        Log.d(TAG, "Processing frame")

        try {
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
            Log.d(TAG, "Created MPImage")

            val result = faceLandmarker.detect(mpImage)
            Log.d(TAG, "Detected faces: ${result.faceLandmarks().size}")

            processDetectionResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
            updateStatus("Error processing frame: ${e.message}", 0f, 0f)
        }
    }

    private var mediaPlayer: MediaPlayer? = null

    private fun playAlarm() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound)
            mediaPlayer?.isLooping = true
        }
        mediaPlayer?.start()
    }

    private fun stopAlarm() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun processDetectionResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) {
            updateStatus("No face detected - Eye Open", 0f, 0f)
            resetDrowsyState()
            return
        }

        val landmarks = result.faceLandmarks()[0]

        val leftEyeEAR = calculateEAR(
            landmarks[133], landmarks[159], landmarks[160],
            landmarks[33], landmarks[144], landmarks[145]
        )

        val rightEyeEAR = calculateEAR(
            landmarks[263], landmarks[386], landmarks[387],
            landmarks[362], landmarks[373], landmarks[374]
        )

        var avgEAR = (leftEyeEAR + rightEyeEAR) / 2
        
        avgEAR = validateEAR(avgEAR)
        
        earHistory.add(avgEAR)
        if (earHistory.size > EAR_HISTORY_SIZE) {
            earHistory.removeFirst()
        }
        
        val filteredEAR = calculateFilteredEAR()
        
        Log.d(TAG, "EAR - Left: ${String.format("%.3f", leftEyeEAR)}, Right: ${String.format("%.3f", rightEyeEAR)}, Avg: ${String.format("%.3f", avgEAR)}, Filtered: ${String.format("%.3f", filteredEAR)}, Threshold: $EAR_THRESHOLD")

        val mouthMAR = calculateMAR(
            landmarks[10], landmarks[18], landmarks[61], landmarks[291]
        )

        var statusMessage = ""
        var shouldAlarm = false

        if (filteredEAR < EAR_THRESHOLD) {
            eyeOpenStartTime = 0
            consecutiveEyesClosedFrames++
            if (consecutiveEyesClosedFrames >= CONSECUTIVE_FRAMES_THRESHOLD) {
                if (!isDrowsy) {
                    isDrowsy = true
                    drowsyStartTime = System.currentTimeMillis()
                    statusMessage = "Eye Closed"
                } else {
                    val drowsyDuration = System.currentTimeMillis() - drowsyStartTime
                    if (drowsyDuration >= EYES_CLOSED_TIME_THRESHOLD) {
                        statusMessage = "Drowsy ${drowsyDuration / 1000}s"
                        shouldAlarm = true
                    } else {
                        statusMessage = "Eye Closed"
                    }
                }
            } else {
                statusMessage = "Eye Open"
            }
        } else {
            consecutiveEyesClosedFrames = 0
            if (mouthMAR > MAR_THRESHOLD) {
                eyeOpenStartTime = 0
                if (!isDrowsy) {
                    isDrowsy = true
                    drowsyStartTime = System.currentTimeMillis()
                    statusMessage = "Yawning"
                } else {
                    val yawnDuration = System.currentTimeMillis() - drowsyStartTime
                    if (yawnDuration >= YAWN_TIME_THRESHOLD) {
                        statusMessage = "Yawning ${yawnDuration / 1000}s"
                        shouldAlarm = true
                    } else {
                        statusMessage = "Yawning"
                    }
                }
            } else {
                if (isAlarmActive) {
                    if (eyeOpenStartTime == 0) {
                        eyeOpenStartTime = System.currentTimeMillis()
                    }
                    val eyeOpenDuration = System.currentTimeMillis() - eyeOpenStartTime
                    if (eyeOpenDuration >= ALARM_RELEASE_DELAY) {
                        resetDrowsyState()
                        statusMessage = "Eye Open"
                    } else {
                        statusMessage = "Releasing... ${(ALARM_RELEASE_DELAY - eyeOpenDuration) / 1000}s"
                    }
                } else {
                    resetDrowsyState()
                    statusMessage = "Eye Open"
                }
            }
        }

        updateStatus(statusMessage, filteredEAR, mouthMAR)

        if (shouldAlarm && mediaPlayer?.isPlaying != true) {
            playAlarm()
            isAlarmActive = true
        }
    }

    private fun updateStatus(message: String, ear: Float, mar: Float) {
        runOnUiThread {
            statusText.text = "$message\nEAR: ${String.format("%.2f", ear)}\nMAR: ${String.format("%.2f", mar)}"
        }
    }

    private fun resetDrowsyState() {
        isDrowsy = false
        isAlarmActive = false
        eyeOpenStartTime = 0
        stopAlarm()
        earHistory.clear()
        consecutiveEyesClosedFrames = 0
    }
    
    private fun validateEAR(ear: Float): Float {
        if (ear < MIN_VALID_EAR || ear > MAX_VALID_EAR) {
            return earHistory.lastOrNull() ?: EAR_THRESHOLD
        }
        return ear
    }
    
    private fun calculateFilteredEAR(): Float {
        if (earHistory.isEmpty()) {
            return EAR_THRESHOLD + 0.1f
        }
        val sorted = earHistory.sorted()
        val startIndex = sorted.size / 4
        val endIndex = sorted.size * 3 / 4
        val filtered = sorted.subList(startIndex, endIndex)
        return if (filtered.isEmpty()) {
            earHistory.average().toFloat()
        } else {
            filtered.average().toFloat()
        }
    }

    private fun calculateMAR(
        upperLip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        lowerLip: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        leftCorner: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        rightCorner: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float {
        val vertDistance = euclideanDistance(upperLip, lowerLip)
        val horzDistance = euclideanDistance(leftCorner, rightCorner)
        
        if (horzDistance == 0f) return 0f
        
        return vertDistance / horzDistance
    }

    private fun calculateEAR(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p3: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p4: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p5: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p6: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float {
        val vertDistance1 = euclideanDistance(p2, p6)
        val vertDistance2 = euclideanDistance(p3, p5)

        val horzDistance = euclideanDistance(p1, p4)

        return (vertDistance1 + vertDistance2) / (2.0f * horzDistance)
    }

    private fun euclideanDistance(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float {
        return sqrt(
            (p1.x() - p2.x()).pow(2) + (p1.y() - p2.y()).pow(2)
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                setupFaceLandmarker()
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceLandmarker?.close()
    }
}
