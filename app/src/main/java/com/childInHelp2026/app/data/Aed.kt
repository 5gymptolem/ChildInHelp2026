package com.childInHelp2026.app.data

data class Aed(
    val id: String = "",

    // Canonical / νέο schema
    val locationName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val address: String = "",
    val notes: String = "",
    val contactPhone: String = "",
    val contactEmail: String = "",
    val active: Boolean = true,

    // Backward-friendly aliases για παλιότερο UI / data usage
    val name: String = "",
    val description: String = "",
    val contactName: String = ""
) {
    fun displayName(): String {
        return when {
            locationName.isNotBlank() -> locationName
            name.isNotBlank() -> name
            else -> "AED"
        }
    }

    fun displayDescription(): String {
        return when {
            notes.isNotBlank() -> notes
            description.isNotBlank() -> description
            else -> ""
        }
    }
}