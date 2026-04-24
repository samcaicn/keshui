package com.example.drowze

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class DetectionService : Service() {
    companion object {
        private const val TAG = "DetectionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "drowze_detection_channel"
        
        // Intent actions
        const val ACTION_START_DETECTION = "com.example.drowze.START_DETECTION"
        const val ACTION_STOP_DETECTION = "com.example.drowze.STOP_DETECTION"
    }
    
    private var isRunning = false
    private lateinit var executor: ScheduledExecutorService
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        executor = Executors.newSingleThreadScheduledExecutor()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service received command: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_DETECTION -> startDetection()
            ACTION_STOP_DETECTION -> stopDetection()
        }
        
        return START_STICKY
    }
    
    private fun startDetection() {
        if (isRunning) return
        
        isRunning = true
        Log.d(TAG, "Starting drowze detection service")
        
        // Start foreground service with notification
        startForeground(NOTIFICATION_ID, createNotification("drowze detection is active"))
        
        // Start detection loop
        executor.scheduleAtFixedRate({
            // This is where we would process camera frames
            // Since we've moved the detection logic to MainActivity for simplicity
            // this service acts primarily as a foreground process keeper
            Log.d(TAG, "Detection service running")
        }, 0, 1, TimeUnit.SECONDS)
    }
    
    private fun stopDetection() {
        if (!isRunning) return
        
        isRunning = false
        Log.d(TAG, "Stopping drowze detection service")
        
        // Stop the foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        stopSelf()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "drowze Detection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Alerts when driver appears drowze"
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("drowze Detection")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        Log.d(TAG, "Service destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}