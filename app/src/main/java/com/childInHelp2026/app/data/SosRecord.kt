package com.childInHelp2026.app.data

data class BiometricReading(
    val timestamp: Long = 0L,
    val heartRate: Int = 0,
    val bloodOxygen: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class SosRecord(
    val id: String = "",
    val createdByUid: String = "",
    val createdByEmail: String = "",
    val createdByName: String = "",
    val createdByPhone: String = "",
    val createdByRole: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val type: String = "",
    val message: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val status: String = "",
    val source: String = "",
    val responderCount: Int = 0,
    val responders: Map<String, Responder> = emptyMap(),
    val biometricHistory: List<BiometricReading> = emptyList()
)