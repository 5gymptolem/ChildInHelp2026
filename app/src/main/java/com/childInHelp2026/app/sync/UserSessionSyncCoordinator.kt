package com.childInHelp2026.app.sync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.childInHelp2026.app.firebase.AppFirebase
import com.childInHelp2026.app.repository.AuthRepository
import java.nio.charset.StandardCharsets
import java.util.Locale

class UserSessionSyncCoordinator(
    private val context: Context,
    private val authRepository: AuthRepository,
    private val database: FirebaseDatabase,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val onRoleLoaded: (String) -> Unit,
    private val onLocationUpdated: (Location) -> Unit,
    private val onSyncError: (String) -> Unit
) {

    companion object {
        private const val TAG = "UserSessionSync"
        private const val ROLE_USER = "user"
    }

    fun ensureCurrentUserProfileThenSync(
        existingLocationListener: ValueEventListener?,
        setLocationListener: (ValueEventListener) -> Unit
    ) {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser == null) {
            Log.d(TAG, "No authenticated user. Skipping bootstrap and sync.")
            return
        }

        authRepository.ensureCurrentUserProfileExists(
            onSuccess = {
                loadUserRole(currentUser.uid)
                syncFcmTokenForCurrentUser()
                observeCurrentUserLocation(
                    uid = currentUser.uid,
                    existingLocationListener = existingLocationListener,
                    setLocationListener = setLocationListener
                )
                fetchAndSaveLocation()
            },
            onError = { errorMessage ->
                Log.e(TAG, "Failed to ensure user profile: $errorMessage")
                onSyncError(errorMessage)
            }
        )
    }

    fun removeCurrentUserLocationListener(listener: ValueEventListener?) {
        val currentUser = authRepository.getCurrentUser()
        if (currentUser != null && listener != null) {
            AppFirebase.rootRef
                .child("user_locations")
                .child(currentUser.uid)
                .removeEventListener(listener)
        }
    }

    private fun loadUserRole(uid: String) {
        AppFirebase.rootRef
            .child("users")
            .child(uid)
            .child("role")
            .get()
            .addOnSuccessListener { snapshot ->
                val role = snapshot.getValue(String::class.java)?.lowercase(Locale.ROOT) ?: ROLE_USER
                Log.d(TAG, "Loaded role for uid=$uid -> $role")
                onRoleLoaded(role)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to load role for uid=$uid", exception)
                onRoleLoaded(ROLE_USER)
            }
    }

    private fun observeCurrentUserLocation(
        uid: String,
        existingLocationListener: ValueEventListener?,
        setLocationListener: (ValueEventListener) -> Unit
    ) {
        if (existingLocationListener != null) return

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val latitude = snapshot.child("latitude").getValue(Double::class.java)
                val longitude = snapshot.child("longitude").getValue(Double::class.java)
                val accuracy = snapshot.child("accuracy").getValue(Double::class.java)

                if (latitude == null || longitude == null) return

                val location = Location("firebase").apply {
                    this.latitude = latitude
                    this.longitude = longitude
                    this.accuracy = (accuracy ?: 0.0).toFloat()
                }

                onLocationUpdated(location)
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                onSyncError("Αποτυχία φόρτωσης θέσης χρήστη: ${error.message}")
            }
        }

        AppFirebase.rootRef
            .child("user_locations")
            .child(uid)
            .addValueEventListener(listener)

        setLocationListener(listener)
    }

    private fun syncFcmTokenForCurrentUser() {
        val currentUser = authRepository.getCurrentUser() ?: return

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isBlank()) return@addOnSuccessListener

                saveUserToken(
                    uid = currentUser.uid,
                    email = currentUser.email,
                    token = token
                )
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to fetch FCM token.", exception)
            }
    }

    private fun saveUserToken(uid: String, email: String?, token: String) {
        val tokenId = encodeToken(token)

        val tokenRecord = hashMapOf<String, Any?>(
            "token" to token,
            "platform" to "android",
            "active" to true,
            "updatedAt" to System.currentTimeMillis(),
            "uid" to uid,
            "email" to (email ?: "")
        )

        database
            .getReference("user_tokens")
            .child(uid)
            .child(tokenId)
            .setValue(tokenRecord)
            .addOnSuccessListener {
                Log.d(TAG, "FCM token saved successfully for uid=$uid")
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to save FCM token for uid=$uid", exception)
            }
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndSaveLocation() {
        val currentUser = authRepository.getCurrentUser() ?: return

        if (!hasAnyLocationPermission()) {
            Log.d(TAG, "Location permission missing. Skipping location sync.")
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    saveLocationToDatabase(currentUser.uid, location)
                } else {
                    fetchCurrentLocationFallback(currentUser.uid)
                }
            }
            .addOnFailureListener {
                fetchCurrentLocationFallback(currentUser.uid)
            }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocationFallback(uid: String) {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .build()

        fusedLocationClient.getCurrentLocation(request, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    saveLocationToDatabase(uid, location)
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "getCurrentLocation failed.", exception)
            }
    }

    private fun saveLocationToDatabase(uid: String, location: Location) {
        val locationData = hashMapOf<String, Any>(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy.toDouble(),
            "updatedAt" to System.currentTimeMillis()
        )

        database
            .getReference("user_locations")
            .child(uid)
            .setValue(locationData)
            .addOnSuccessListener {
                Log.d(TAG, "Location saved successfully for uid=$uid")
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to save location for uid=$uid", exception)
            }
    }

    private fun hasAnyLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    private fun encodeToken(token: String): String {
        return Base64.encodeToString(
            token.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
    }
}