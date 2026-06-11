package com.childInHelp2026.app.data

data class UserProfile(
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val area: String = "",
    val certifiedFirstAid: Boolean = false,
    val preferredRadiusKm: Int = 10,
    val notificationsEnabled: Boolean = true,
    val active: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)