package com.childInHelp2026.app.data

data class Responder(
    val uid: String = "",
    val email: String = "",
    val fullName: String = "",
    val phone: String = "",
    val role: String = "",
    val status: String = "",
    val startedAt: Long = 0L,
    val lastUpdatedAt: Long = 0L,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)