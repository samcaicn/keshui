package com.example.drowze

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

    fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}