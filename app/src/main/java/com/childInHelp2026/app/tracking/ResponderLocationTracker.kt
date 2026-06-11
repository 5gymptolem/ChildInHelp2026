package com.childInHelp2026.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.childInHelp2026.app.firebase.AppFirebase

class ResponderLocationTracker(
    private val context: Context
) {

    companion object {
        private const val TAG = "ResponderTracker"
        private const val DEFAULT_UPDATE_INTERVAL_MS = 5000L
        private const val MIN_UPDATE_INTERVAL_MS = 2000L
        private const val STATUS_ACTIVE = "ACTIVE"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fusedLocationClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var isTracking = false
    private var currentSosId: String? = null
    private var currentUserUid: String? = null
    private var updateIntervalMs: Long = DEFAULT_UPDATE_INTERVAL_MS

    fun updateConfig(newUpdateIntervalMs: Long) {
        val sanitized = when {
            newUpdateIntervalMs < MIN_UPDATE_INTERVAL_MS -> MIN_UPDATE_INTERVAL_MS
            else -> newUpdateIntervalMs
        }

        val changed = sanitized != updateIntervalMs
        updateIntervalMs = sanitized

        if (changed) {
            Log.d(TAG, "Tracking interval updated to $updateIntervalMs ms")

            if (isTracking) {
                handler.removeCallbacks(updateRunnable)
                handler.postDelayed(updateRunnable, updateIntervalMs)
            }
        }
    }

    fun startTracking(sosId: String, userUid: String) {
        currentSosId = sosId
        currentUserUid = userUid

        if (isTracking) {
            Log.d(TAG, "Tracking already active. Switching target to sosId=$sosId uid=$userUid")
            return
        }

        isTracking = true
        handler.post(updateRunnable)
        Log.d(TAG, "Started tracking for SOS: $sosId")
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        handler.removeCallbacks(updateRunnable)
        Log.d(TAG, "Stopped tracking")
    }

    fun isCurrentlyTracking(): Boolean = isTracking

    fun getCurrentTrackedSosId(): String? = currentSosId

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isTracking) return

            ensureSosIsStillActiveThenUpdate()

            if (isTracking) {
                handler.postDelayed(this, updateIntervalMs)
            }
        }
    }

    private fun ensureSosIsStillActiveThenUpdate() {
        val sosId = currentSosId ?: run {
            stopTracking()
            return
        }

        AppFirebase.rootRef
            .child("sos")
            .child(sosId)
            .child("status")
            .get()
            .addOnSuccessListener { snapshot ->
                val status = snapshot.getValue(String::class.java)?.trim()?.uppercase()

                if (status != STATUS_ACTIVE) {
                    Log.d(TAG, "SOS is no longer ACTIVE. Stopping tracking for sosId=$sosId")
                    stopTracking()
                    return@addOnSuccessListener
                }

                sendLocationUpdate()
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to verify SOS status before location update", exception)
            }
    }

    @SuppressLint("MissingPermission")
    private fun sendLocationUpdate() {
        val sosId = currentSosId ?: return
        val uid = currentUserUid ?: return

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    writeLocation(sosId, uid, location)
                } else {
                    Log.d(TAG, "lastLocation returned null")
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Location fetch failed", exception)
            }
    }

    private fun writeLocation(sosId: String, uid: String, location: Location) {
        val updates = hashMapOf<String, Any>(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "lastUpdatedAt" to System.currentTimeMillis()
        )

        AppFirebase.rootRef
            .child("sos")
            .child(sosId)
            .child("responders")
            .child(uid)
            .updateChildren(updates)
            .addOnSuccessListener {
                Log.d(
                    TAG,
                    "Responder location updated: sosId=$sosId uid=$uid lat=${location.latitude} lng=${location.longitude}"
                )
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to update responder location", exception)
            }
    }
}