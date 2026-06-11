package com.childInHelp2026.app.bluetooth

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {

    companion object {
        private const val TAG = "HealthConnectManager"
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        )
    }

    private val healthConnectClient by lazy {
        if (isHealthConnectAvailable()) HealthConnectClient.getOrCreate(context) else null
    }

    fun isHealthConnectAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    suspend fun revokePermissions() {
        val client = healthConnectClient ?: return
        try {
            client.permissionController.revokeAllPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "Error revoking permissions", e)
        }
    }

    suspend fun getLatestHeartRate(): Int {
        val client = healthConnectClient ?: return 0
        try {
            val endTime = Instant.now()
            val startTime = endTime.minus(10, ChronoUnit.MINUTES) // Look at the last 10 minutes

            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                )
            )

            // Get the very last sample from the last record
            val lastRecord = response.records.lastOrNull()
            val lastSample = lastRecord?.samples?.lastOrNull()
            
            return lastSample?.beatsPerMinute?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error reading heart rate", e)
            return 0
        }
    }

    suspend fun getLatestSpO2(): Int {
        val client = healthConnectClient ?: return 0
        try {
            val endTime = Instant.now()
            val startTime = endTime.minus(30, ChronoUnit.MINUTES) // SpO2 is measured less frequently

            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )

            val lastRecord = response.records.lastOrNull()
            return lastRecord?.percentage?.value?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SpO2", e)
            return 0
        }
    }
}
