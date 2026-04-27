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
import com.example.drowze.sdk.DrowzeDetector
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.VIBRATE
        )

        private const val DROWSY_TIME_THRESHOLD = 5000
        private const val SOS_COOLDOWN_TIME = 30000
    }

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var alertStatusText: TextView
    private lateinit var manageContactsButton: Button
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var drowzeDetector: DrowzeDetector

    private var isDrowsy = false
    private var drowsyStartTime: Long = 0
    private var lastSOSSentTime: Long = 0
    private var isSendingMessages = false

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        statusText = findViewById(R.id.status_text)
        alertStatusText = findViewById(R.id.alert_status_text)
        manageContactsButton = findViewById(R.id.manage_contacts_button)

        manageContactsButton.setOnClickListener {
            startActivity(Intent(this, ContactsActivity::class.java))
        }

        // Initialize DrowzeDetector from SDK
        drowzeDetector = DrowzeDetector(this)
        drowzeDetector.setListener(object : DrowzeDetector.DetectionListener {
            override fun onDrowsy(duration: Long) {
                handleDrowsyState(duration)
            }

            override fun onAlert(message: String) {
                updateStatus(message)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Detector error: $error")
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            }
        })

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        val serviceIntent = Intent(this, DetectionService::class.java).apply {
            action = DetectionService.ACTION_START_DETECTION
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview
                )

                // Initialize detector after camera is started
                drowzeDetector.initialize()
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
                captureCameraFrame()
                handler.postDelayed(this, 200)
            }
        }

        handler.post(frameCapture)
    }

    private fun captureCameraFrame() {
        val bitmap = previewView.bitmap ?: return

        val rotatedBitmap = rotateBitmap(bitmap, 0f)

        // Use DrowzeDetector from SDK to process frame
        drowzeDetector.detect(rotatedBitmap)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

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

    private fun vibratePhone() {
        drowzeDetector.vibrate()
    }

    private fun stopVibration() {
        drowzeDetector.stopVibration()
    }

    private fun handleDrowsyState(drowsyDuration: Long) {
        val timeSinceLastSOS = System.currentTimeMillis() - lastSOSSentTime

        if (!isSendingMessages && timeSinceLastSOS > SOS_COOLDOWN_TIME) {
            sendSOSMessages(drowsyDuration)
            updateAlertStatus("Sending SOS...", R.color.warning_yellow)
        } else if (timeSinceLastSOS <= SOS_COOLDOWN_TIME) {
            updateAlertStatus("SOS cooldown: ${(SOS_COOLDOWN_TIME - timeSinceLastSOS) / 1000}s", R.color.warning_yellow)
        }
    }

    private fun sendSOSMessages(drowsyDuration: Long) {
        val contacts = Utils.getContacts(this)
        if (contacts.isEmpty()) {
            updateAlertStatus("No emergency contacts found", R.color.error_red)
            return
        }

        isSendingMessages = true
        lastSOSSentTime = System.currentTimeMillis()

        val timestamp = Utils.formatTimestamp(System.currentTimeMillis())

        val message = "EMERGENCY ALERT: Driver drowze detected for ${drowsyDuration / 1000} seconds at $timestamp."

        vibratePhone()
        playAlarm()

        updateStatus("DROWZE ALERT! WAKE UP!\nAlert Ready")
        updateAlertStatus("Alert prepared for emergency contacts", R.color.warning_yellow)

        Thread {
            try {
                runOnUiThread {
                    updateAlertStatus("Alert prepared successfully", R.color.success_green)
                    updateStatus("DROWZE ALERT! WAKE UP!\nAlert Ready")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing alert", e)
                runOnUiThread {
                    updateAlertStatus("Failed to prepare alert", R.color.error_red)
                }
            } finally {
                isSendingMessages = false
            }
        }.start()
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    private fun updateAlertStatus(message: String, colorResId: Int) {
        runOnUiThread {
            alertStatusText.text = message
            alertStatusText.setTextColor(ContextCompat.getColor(this, colorResId))
        }
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
        drowzeDetector.release()

        val serviceIntent = Intent(this, DetectionService::class.java).apply {
            action = DetectionService.ACTION_STOP_DETECTION
        }
        startService(serviceIntent)
    }
}