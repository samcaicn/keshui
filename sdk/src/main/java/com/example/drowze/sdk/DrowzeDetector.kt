package com.example.drowze.sdk

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

class DrowzeDetector(private val context: Context) {
    companion object {
        private const val TAG = "DrowzeDetector"
    }

    interface DetectionListener {
        fun onError(error: String)
    }

    private var listener: DetectionListener? = null

    fun setListener(listener: DetectionListener) {
        this.listener = listener
    }

    fun initialize(modelPath: String? = null) {
        if (isDebuggerAttached()) {
            listener?.onError("Debugger detected")
            return
        }

        Log.d(TAG, "DrowzeDetector initialized")
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
        listener = null
    }

    private fun isDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }
}