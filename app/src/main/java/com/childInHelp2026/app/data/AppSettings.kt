package com.childInHelp2026.app.data

data class ResponderTrackingSettings(
    val enabled: Boolean = true,
    val staleTimeoutMs: Long = 15000L,
    val updateIntervalMs: Long = 5000L,
    val showArrivedResponders: Boolean = true,
    val showCancelledResponders: Boolean = false
)

data class NotificationRoutingSettings(
    val mode: String = "ALL_IN_RADIUS",
    val notifyCertifiedOnly: Boolean = false,
    val notifyNonCertifiedAlso: Boolean = true,
    val includeCreatorInNotifications: Boolean = false
)

data class AppSettings(
    val defaultRadiusKm: Int = 10,
    val maxRadiusKm: Int = 50,
    val allowCustomRadius: Boolean = true,

    // old flat notification fields - keep for backward compatibility
    val notifyCertifiedOnly: Boolean = false,
    val notifyNonCertifiedAlso: Boolean = true,

    val maxLocationAgeMinutes: Int = 30,

    // old flat tracking fields - keep for backward compatibility
    val responderTrackingEnabled: Boolean = true,
    val responderTrackingIntervalSeconds: Int = 15,

    val appName: String ="@string/app_name",
    val footerText: String = "@String/Dev_by",

    // new nested config blocks
    val responderTracking: ResponderTrackingSettings = ResponderTrackingSettings(),
    val notificationRouting: NotificationRoutingSettings = NotificationRoutingSettings()
) {
    fun effectiveResponderTracking(): ResponderTrackingSettings {
        val nested = responderTracking

        return nested.copy(
            enabled = nested.enabled,
            updateIntervalMs = if (nested.updateIntervalMs > 0L) {
                nested.updateIntervalMs
            } else {
                responderTrackingIntervalSeconds * 1000L
            }
        )
    }

    fun effectiveNotificationRouting(): NotificationRoutingSettings {
        val nested = notificationRouting

        return nested.copy(
            notifyCertifiedOnly = nested.notifyCertifiedOnly,
            notifyNonCertifiedAlso = nested.notifyNonCertifiedAlso
        )
    }
}