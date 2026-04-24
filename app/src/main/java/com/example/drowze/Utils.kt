package com.example.drowze

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Utils {
    private const val PREFS_NAME = "DrowsinessDetectionPrefs"
    private const val CONTACTS_KEY = "emergency_contacts"

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveContacts(context: Context, contacts: List<Contact>) {
        val prefs = getSharedPreferences(context)
        val gson = Gson()
        val json = gson.toJson(contacts)
        prefs.edit().putString(CONTACTS_KEY, json).apply()
    }

    fun getContacts(context: Context): List<Contact> {
        val prefs = getSharedPreferences(context)
        val gson = Gson()
        val json = prefs.getString(CONTACTS_KEY, null) ?: return emptyList()

        val type = object : TypeToken<List<Contact>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e("Utils", "Error parsing contacts", e)
            emptyList()
        }
    }

    fun sendSMS(phoneNumber: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()

            // Split message if it's too long
            val messageParts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, messageParts, null, null)

            Log.d("Utils", "SMS sent to $phoneNumber")
        } catch (e: Exception) {
            Log.e("Utils", "Failed to send SMS", e)
            throw e
        }
    }

    // Helper function to format timestamps in a user-friendly way
    fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    // Track the last known location
    private var lastKnownLocation: Location? = null

    // Location client for the whole application
    private var fusedLocationClient: FusedLocationProviderClient? = null

    // Initialize location services
    fun initLocationServices(context: Context) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        startLocationUpdates(context)

        // Get initial location
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                if (location != null) {
                    lastKnownLocation = location
                }
            }
        }
    }

    // Start receiving location updates
    private fun startLocationUpdates(context: Context) {
        // Check if we have permission
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("Utils", "Location permissions not granted")
            return
        }

        // Location request settings
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000) // 5 seconds
            .setMinUpdateIntervalMillis(2000) // 2 seconds
            .setMaxUpdateDelayMillis(10000) // 10 seconds
            .setWaitForAccurateLocation(true)
            .build()

        // Location callback
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    lastKnownLocation = location
                    Log.d("Utils", "Location updated: ${location.latitude}, ${location.longitude}")
                }
            }
        }

        // Request location updates
        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e("Utils", "Error requesting location updates", e)
        }
    }

    // Get the current location as a formatted string for SOS messages
    private lateinit var context: Context

    fun initContext(appContext: Context) {
        context = appContext
        initLocationServices(context)
    }

    fun getLocationInfo(callback: (String) -> Unit) {
        if (!::context.isInitialized) {
            callback("Location information unavailable")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            callback("Location unavailable (permissions not granted)")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            callback("Location unavailable (GPS is disabled)")
            return
        }

        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            if (location != null) {
                lastKnownLocation = location
                val lat = location.latitude
                val lng = location.longitude
                callback("Current location: https://map.baidu.com/?q=${lat},${lng}")
            } else {
                // Request a fresh location update
                val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                    .setMinUpdateIntervalMillis(2000)
                    .setMaxUpdateDelayMillis(10000)
                    .setWaitForAccurateLocation(true)
                    .build()

                val locationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        locationResult.lastLocation?.let { newLocation ->
                            lastKnownLocation = newLocation
                            val lat = newLocation.latitude
                            val lng = newLocation.longitude
                            callback("Current location: https://map.baidu.com/?q=${lat},${lng}")
                        }
                    }
                }

                fusedLocationClient?.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )

                callback("Acquiring location, please wait...")
            }
        }?.addOnFailureListener {
            callback("Failed to retrieve location")
        }
    }
}
