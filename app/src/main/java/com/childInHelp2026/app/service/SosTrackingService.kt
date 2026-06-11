package com.childInHelp2026.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.childInHelp2026.app.MainActivity
import com.childInHelp2026.app.bluetooth.BluetoothBiometricManager
import com.childInHelp2026.app.bluetooth.BluetoothPreferences
import com.childInHelp2026.app.bluetooth.HealthConnectManager
import com.childInHelp2026.app.data.BiometricReading
import com.childInHelp2026.app.data.SosRecord
import com.childInHelp2026.app.firebase.AppFirebase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SosTrackingService : Service() {

    companion object {
        private const val TAG = "SosTrackingService"
        private const val CHANNEL_ID = "sos_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL_MS = 30 * 1000L // 5 minutes

        const val EXTRA_SOS_ID = "extra_sos_id"
    }

    private lateinit var biometricManager: BluetoothBiometricManager
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var sosId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastLocation: Location? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateSosData()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        biometricManager = BluetoothBiometricManager(this)
        healthConnectManager = HealthConnectManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sosId = intent?.getStringExtra(EXTRA_SOS_ID)
        
        if (sosId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or 
                      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val btPrefs = BluetoothPreferences(this)
        if (btPrefs.isEnabled) {
            biometricManager.startScanning(btPrefs.deviceAddress)
        }

        startLocationUpdates()
        handler.post(updateRunnable)

        observeSosStatus()

        return START_STICKY
    }

    private fun observeSosStatus() {
        sosId?.let { id ->
            AppFirebase.rootRef.child("sos").child(id).child("status")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val status = snapshot.getValue(String::class.java)
                        if (status != "ACTIVE") {
                            Log.d(TAG, "SOS is no longer active. Stopping service.")
                            stopSelf()
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lastLocation = result.lastLocation
        }
    }

    private fun updateSosData() {
        val id = sosId ?: return
        val location = lastLocation ?: return
        
        serviceScope.launch {
            var currentHR = biometricManager.currentHeartRate
            var currentSpO2 = biometricManager.currentSpO2

            try {
                if (healthConnectManager.isHealthConnectAvailable() && healthConnectManager.hasAllPermissions()) {
                    val hcHR = healthConnectManager.getLatestHeartRate()
                    val hcSpO2 = healthConnectManager.getLatestSpO2()
                    
                    if (hcHR > 0) currentHR = hcHR
                    if (hcSpO2 > 0) currentSpO2 = hcSpO2
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching from Health Connect", e)
            }

            val reading = BiometricReading(
                timestamp = System.currentTimeMillis(),
                heartRate = currentHR,
                bloodOxygen = currentSpO2,
                latitude = location.latitude,
                longitude = location.longitude
            )

            val sosRef = AppFirebase.rootRef.child("sos").child(id)
            sosRef.get().addOnSuccessListener { snapshot ->
                val sosRecord = snapshot.getValue(SosRecord::class.java) ?: return@addOnSuccessListener
                val newHistory = sosRecord.biometricHistory.toMutableList()
                newHistory.add(reading)
                
                val updates = mapOf(
                    "biometricHistory" to newHistory,
                    "latitude" to location.latitude,
                    "longitude" to location.longitude,
                    "updatedAt" to System.currentTimeMillis()
                )
                
                sosRef.updateChildren(updates)
                    .addOnSuccessListener { Log.d(TAG, "SOS data updated successfully") }
                    .addOnFailureListener { e -> Log.e(TAG, "Failed to update SOS data", e) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        serviceScope.cancel()
        biometricManager.stopScanning()
        biometricManager.disconnect()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SOS_ID, sosId)
            putExtra(MainActivity.EXTRA_OPEN_FROM_NOTIFICATION, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SOS Tracking Active")
            .setContentText("Recording biometric data and location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "SOS Tracking Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
