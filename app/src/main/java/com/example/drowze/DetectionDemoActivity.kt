package com.example.drowze

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.View
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
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt

class DetectionDemoActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "DetectionDemo"
        private const val REQUEST_CODE_PERMISSIONS = 20
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        private const val EAR_THRESHOLD = 0.25f
        private const val DROWSY_TIME_THRESHOLD = 3000L
    }

    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var detectionInfoText: TextView
    private lateinit var startButton: Button
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    private var faceLandmarker: FaceLandmarker? = null
    private var isDetecting = false

    private var isDrowsy = false
    private var drowsyStartTime: Long = 0

    private val eyePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val mouthPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val overlayPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        alpha = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection_demo)

        previewView = findViewById(R.id.preview_view)
        statusText = findViewById(R.id.status_text)
        detectionInfoText = findViewById(R.id.detection_info_text)
        startButton = findViewById(R.id.start_button)

        cameraExecutor = Executors.newSingleThreadExecutor()

        startButton.setOnClickListener {
            if (isDetecting) {
                stopDetection()
            } else {
                startDetection()
            }
        }

        if (allPermissionsGranted()) {
            setupFaceLandmarker()
            startButton.isEnabled = true
            statusText.text = "Ready - Tap Start to begin detection"
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            statusText.text = "Waiting for camera permission..."
            startButton.isEnabled = false
        }
    }

    private fun setupFaceLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setResultListener { result, inputImage ->
                    runOnUiThread {
                        processDetectionResult(result)
                    }
                }
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
            Log.d(TAG, "FaceLandmarker initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up FaceLandmarker", e)
            Toast.makeText(this, "Failed to setup face detection", Toast.LENGTH_LONG).show()
        }
    }

    private fun startDetection() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            return
        }

        isDetecting = true
        startButton.text = "Stop Detection"
        startCamera()
    }

    private fun stopDetection() {
        isDetecting = false
        startButton.text = "Start Detection"
        statusText.text = "Detection stopped"
        resetDrowsyState()

        try {
            cameraProviderFuture?.addListener({ 
                val cameraProvider = cameraProviderFuture?.get()
                cameraProvider?.unbindAll()
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture?.addListener({ 
            val cameraProvider = cameraProviderFuture?.get()
            cameraProvider ?: return@addListener

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview
                )

                setupFrameCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupFrameCapture() {
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val handler = android.os.Handler()
        val frameCapture = object : Runnable {
            override fun run() {
                if (isDetecting) {
                    captureCameraFrame()
                }
                handler.postDelayed(this, 100)
            }
        }

        handler.post(frameCapture)
    }

    private fun captureCameraFrame() {
        val bitmap = previewView.bitmap ?: return
        processFrame(bitmap)
    }

    private fun processFrame(bitmap: Bitmap) {
        val faceLandmarker = faceLandmarker ?: return

        try {
            val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
            val result = faceLandmarker.detect(mpImage)
            processDetectionResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }

    private fun processDetectionResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) {
            updateStatus("No face detected", Color.GRAY)
            detectionInfoText.text = "EAR: ---\nMAR: ---"
            resetDrowsyState()
            return
        }

        val landmarks = result.faceLandmarks()[0]

        val leftEyeEAR = calculateEAR(
            landmarks[33], landmarks[159], landmarks[160],
            landmarks[133], landmarks[144], landmarks[145]
        )

        val rightEyeEAR = calculateEAR(
            landmarks[362], landmarks[386], landmarks[387],
            landmarks[263], landmarks[373], landmarks[374]
        )

        val avgEAR = (leftEyeEAR + rightEyeEAR) / 2

        val mouthMAR = calculateMAR(
            landmarks[13], landmarks[14], landmarks[78],
            landmarks[308], landmarks[61], landmarks[291]
        )

        detectionInfoText.text = "EAR: ${String.format("%.3f", avgEAR)}\nMAR: ${String.format("%.3f", mouthMAR)}"

        if (avgEAR < EAR_THRESHOLD) {
            if (!isDrowsy) {
                isDrowsy = true
                drowsyStartTime = System.currentTimeMillis()
                updateStatus("Eyes closing...", Color.YELLOW)
            } else {
                val drowsyDuration = System.currentTimeMillis() - drowsyStartTime
                if (drowsyDuration >= 2000) {
                    updateStatus("DROWSY! Eyes closed ${drowsyDuration / 1000}s", Color.RED)
                } else {
                    updateStatus("Eyes closed (${drowsyDuration / 1000}s)", Color.YELLOW)
                }
            }
        } else if (mouthMAR > 0.5) {
            if (!isDrowsy) {
                isDrowsy = true
                drowsyStartTime = System.currentTimeMillis()
                updateStatus("Yawning detected!", Color.YELLOW)
            } else {
                val yawnDuration = System.currentTimeMillis() - drowsyStartTime
                updateStatus("DROWSY! Yawning ${yawnDuration / 1000}s", Color.RED)
            }
        } else {
            resetDrowsyState()
            updateStatus("Alert - Watching you", Color.GREEN)
        }
    }

    private fun resetDrowsyState() {
        isDrowsy = false
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

    private fun calculateMAR(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p3: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p4: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p5: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p6: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float {
        val vertDistance1 = euclideanDistance(p1, p2)
        val vertDistance2 = euclideanDistance(p3, p4)
        val horzDistance = euclideanDistance(p5, p6)
        return (vertDistance1 + vertDistance2) / (2.0f * horzDistance)
    }

    private fun euclideanDistance(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float {
        return sqrt((p1.x() - p2.x()).pow(2) + (p1.y() - p2.y()).pow(2))
    }

    private fun updateStatus(message: String, color: Int) {
        statusText.text = message
        statusText.setTextColor(color)
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
                startButton.isEnabled = true
                statusText.text = "Ready - Tap Start to begin detection"
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
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