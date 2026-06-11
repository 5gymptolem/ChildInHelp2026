package com.childInHelp2026.app.ui.sos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.childInHelp2026.app.R
import com.childInHelp2026.app.data.AppSettings
import com.childInHelp2026.app.data.NotificationRoutingSettings
import com.childInHelp2026.app.data.Responder
import com.childInHelp2026.app.data.ResponderTrackingSettings
import com.childInHelp2026.app.data.SosRecord
import com.childInHelp2026.app.repository.SosRepository
import com.childInHelp2026.app.tracking.ResponderLocationTracker
import com.childInHelp2026.app.ui.main.MainMapController
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SosUiController(
    private val context: Context,
    private val mapController: MainMapController,
    private val sosRepository: SosRepository,
    private val getCurrentUserUid: () -> String?,
    private val getCurrentUserEmail: () -> String,
    private val getCurrentUserRole: () -> String,
    private val getCurrentUserLocation: () -> Location?,
    private val onStatusMessage: (String) -> Unit,
    private val onNearestAedRequested: (() -> Unit)?
) {

    companion object {
        private const val ROLE_ADMIN = "admin"
        private const val STATUS_ACTIVE = "ACTIVE"
        private const val RESPONDER_STATUS_RESPONDING = "RESPONDING"
        private const val RESPONDER_STATUS_ARRIVED = "ARRIVED"
        private const val RESPONDER_STATUS_CANCELLED = "CANCELLED"

        private const val FOCUS_ZOOM_LEVEL = 17.0
        private const val FOCUS_CIRCLE_RADIUS_METERS = 50.0
    }

    private val sosMarkers = mutableListOf<Marker>()
    private val responderMarkersByKey = mutableMapOf<String, Marker>()
    private var currentSos: List<SosRecord> = emptyList()
    private val responderTracker = ResponderLocationTracker(context)

    private var appSettings: AppSettings = AppSettings()
    private var responderTrackingSettings: ResponderTrackingSettings =
        AppSettings().effectiveResponderTracking()
    private var notificationRoutingSettings: NotificationRoutingSettings =
        AppSettings().effectiveNotificationRouting()

    private var focusCircle: Polygon? = null
    private var selectedSosId: String? = null
    private var activeDetailsDialog: AlertDialog? = null

    private var activeDetailHeartRateText: TextView? = null
    private var activeDetailOxygenText: TextView? = null
    private var activeDetailBiometricTimeText: TextView? = null
    private var activeDetailCoordsText: TextView? = null
    private var activeDetailMessageText: TextView? = null
    private var activeDetailCreatorText: TextView? = null
    private var activeDetailPhoneText: TextView? = null

    fun updateAppSettings(settings: AppSettings) {
        appSettings = settings
        responderTrackingSettings = settings.effectiveResponderTracking()
        notificationRoutingSettings = settings.effectiveNotificationRouting()
        responderTracker.updateConfig(responderTrackingSettings.updateIntervalMs)
    }

    fun updateSos(sosList: List<SosRecord>, isSosTabActive: Boolean) {
        currentSos = sosList
        enforceTrackingConsistency()
        reconcileSelectionWithLatestData()

        if (isSosTabActive) {
            renderForActiveTab()
        } else {
            renderForInactiveTab()
        }
    }

    fun renderForActiveTab() {
        clearSosMarkers()

        val mapView = mapController.getMapView()
        val sosIcon = ContextCompat.getDrawable(context, R.drawable.ic_sos_marker_v2)

        currentSos.forEach { sos ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(sos.latitude, sos.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = sos.type.ifBlank { "SOS" }
                snippet = buildSosSnippet(sos)
                icon = sosIcon?.constantState?.newDrawable()?.mutate()

                setOnMarkerClickListener { _, _ ->
                    focusOnSosRecord(sos)
                    true
                }
            }

            sosMarkers.add(marker)
            mapView.overlays.add(marker)
        }

        renderResponders()
        restoreFocusCircleIfNeeded()
        mapView.invalidate()

        onStatusMessage(
            if (currentSos.isEmpty()) {
                "Δεν υπάρχουν ενεργά SOS."
            } else {
                "Ενεργά SOS: ${currentSos.size}"
            }
        )

        if (currentSos.isNotEmpty() && !mapController.hasCenteredMap()) {
            val first = currentSos.first()
            mapController.centerOnPoint(GeoPoint(first.latitude, first.longitude))
        }
    }

    fun renderForInactiveTab() {
        clearSosMarkers()
        clearFocusCircle()
        mapController.getMapView().invalidate()
    }

    fun showSosListDialog() {
        if (currentSos.isEmpty()) {
            Toast.makeText(context, "Δεν υπάρχουν ενεργά SOS.", Toast.LENGTH_SHORT).show()
            return
        }

        val items = currentSos.map { sos ->
            buildString {
                append(sos.type.ifBlank { "SOS" })
                append("\n")
                append(formatTimestamp(sos.createdAt))

                val creatorDisplay = getCreatorDisplayName(sos)
                if (creatorDisplay.isNotBlank()) {
                    append("\n")
                    append(creatorDisplay)
                }

                if (sos.message.isNotBlank()) {
                    append("\n")
                    append(sos.message)
                }
            }
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Ενεργά SOS")
            .setItems(items) { _, which ->
                val selected = currentSos[which]
                focusOnSosRecord(selected)
            }
            .setNegativeButton("Κλείσιμο", null)
            .show()
    }

    fun focusOnSosById(sosId: String): Boolean {
        val sos = currentSos.firstOrNull { it.id == sosId } ?: return false
        focusOnSosRecord(sos)
        return true
    }

    private fun focusOnSosRecord(sos: SosRecord) {
        selectedSosId = sos.id

        val point = GeoPoint(sos.latitude, sos.longitude)
        val mapView = mapController.getMapView()

        mapView.controller.setZoom(FOCUS_ZOOM_LEVEL)
        mapController.centerOnPoint(point)
        drawFocusCircle(point)
        showSosDetailsDialog(sos)
    }

    private fun drawFocusCircle(point: GeoPoint) {
        val mapView = mapController.getMapView()

        focusCircle?.let {
            mapView.overlays.remove(it)
        }

        val circle = Polygon().apply {
            points = Polygon.pointsAsCircle(point, FOCUS_CIRCLE_RADIUS_METERS)
            outlinePaint.color = 0xFFFF0000.toInt()
            outlinePaint.strokeWidth = 3f
            fillPaint.color = 0x30FF0000
        }

        focusCircle = circle
        mapView.overlays.add(circle)
        mapView.invalidate()
    }

    private fun clearFocusCircle() {
        val mapView = mapController.getMapView()

        focusCircle?.let {
            mapView.overlays.remove(it)
        }
        focusCircle = null
        mapView.invalidate()
    }

    private fun restoreFocusCircleIfNeeded() {
        val selectedId = selectedSosId ?: return
        val selected = currentSos.firstOrNull { it.id == selectedId } ?: return

        drawFocusCircle(GeoPoint(selected.latitude, selected.longitude))
    }

    private fun reconcileSelectionWithLatestData() {
        val selectedId = selectedSosId ?: return
        val latestSos = currentSos.firstOrNull { it.id == selectedId }

        if (latestSos == null) {
            selectedSosId = null
            clearFocusCircle()

            activeDetailsDialog?.dismiss()
            activeDetailsDialog = null
            clearActiveDialogReferences()

            onStatusMessage("Το επιλεγμένο SOS δεν είναι πλέον ενεργό.")
            return
        }

        // Update UI of existing dialog if it's showing
        if (activeDetailsDialog?.isShowing == true) {
            updateDialogContent(latestSos)
        }
    }

    private fun updateDialogContent(sos: SosRecord) {
        val creatorDisplay = getCreatorDisplayName(sos).ifBlank { "Άγνωστος" }
        
        activeDetailCreatorText?.text = "Δημιουργός: $creatorDisplay"
        activeDetailPhoneText?.text = "Τηλέφωνο: ${sos.createdByPhone.ifBlank { "-" }}"
        activeDetailCoordsText?.text =
            "Συντεταγμένες: ${String.format(Locale.US, "%.6f, %.6f", sos.latitude, sos.longitude)}"
        activeDetailMessageText?.text = "Μήνυμα:\n${sos.message.ifBlank { "-" }}"

        val latestBiometric = sos.biometricHistory.lastOrNull()
        if (latestBiometric != null) {
            activeDetailHeartRateText?.text = if (latestBiometric.heartRate > 0) {
                context.getString(R.string.heart_rate_label, latestBiometric.heartRate)
            } else {
                "Καρδιακός παλμός: -"
            }

            activeDetailOxygenText?.text = if (latestBiometric.bloodOxygen > 0) {
                context.getString(R.string.oxygen_label, latestBiometric.bloodOxygen)
            } else {
                "Οξυγόνο: -"
            }

            activeDetailBiometricTimeText?.text = context.getString(
                R.string.biometric_stale_time,
                formatTimestamp(latestBiometric.timestamp)
            )
        } else {
            activeDetailHeartRateText?.text = context.getString(R.string.no_biometric_data)
            activeDetailOxygenText?.text = ""
            activeDetailBiometricTimeText?.text = ""
        }
    }

    private fun clearActiveDialogReferences() {
        activeDetailHeartRateText = null
        activeDetailOxygenText = null
        activeDetailBiometricTimeText = null
        activeDetailCoordsText = null
        activeDetailMessageText = null
        activeDetailCreatorText = null
        activeDetailPhoneText = null
    }

    private fun showSosDetailsDialog(sos: SosRecord) {
        activeDetailsDialog?.dismiss()

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_sos_details, null)

        val textType = view.findViewById<TextView>(R.id.textType)
        val textDate = view.findViewById<TextView>(R.id.textDate)
        activeDetailCreatorText = view.findViewById(R.id.textCreator)
        activeDetailPhoneText = view.findViewById(R.id.textPhone)
        activeDetailCoordsText = view.findViewById(R.id.textCoords)
        activeDetailHeartRateText = view.findViewById(R.id.textHeartRate)
        activeDetailOxygenText = view.findViewById(R.id.textOxygen)
        activeDetailBiometricTimeText = view.findViewById(R.id.textBiometricTime)
        activeDetailMessageText = view.findViewById(R.id.textMessage)

        val btnNavigate = view.findViewById<MaterialButton>(R.id.btnNavigate)
        val btnNearestAed = view.findViewById<MaterialButton>(R.id.btnNearestAed)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btnCopy)

        textType.text = sos.type.ifBlank { "SOS" }
        textDate.text = "Ημερομηνία: ${formatTimestamp(sos.createdAt)}"
        
        updateDialogContent(sos)

        btnNavigate.setOnClickListener {
            openGoogleNavigation(sos)
        }

        btnCopy.setOnClickListener {
            copyCoordinatesToClipboard(sos)
        }

        btnNearestAed.setOnClickListener {
            if (onNearestAedRequested != null) {
                onNearestAedRequested.invoke()
            } else {
                Toast.makeText(context, "Δεν υπάρχει διαθέσιμος απινιδωτής.", Toast.LENGTH_SHORT)
                    .show()
            }
        }

        val builder = AlertDialog.Builder(context)
            .setView(view)
            .setNegativeButton("Κλείσιμο", null)

        val canDeactivate = canDeactivate(sos)
        val currentUid = getCurrentUserUid()
        val myResponder = if (currentUid.isNullOrBlank()) null else sos.responders[currentUid]
        val myStatus = myResponder?.status?.trim()?.uppercase(Locale.ROOT)

        when (myStatus) {
            RESPONDER_STATUS_RESPONDING -> {
                builder.setNeutralButton("Έφτασα") { _, _ ->
                    markArrived(sos)
                }
            }

            RESPONDER_STATUS_CANCELLED,
            null,
            "" -> {
                builder.setNeutralButton("Κατευθύνομαι") { _, _ ->
                    confirmRespondToSos(sos)
                }
            }

            RESPONDER_STATUS_ARRIVED -> {
            }
        }

        if (canDeactivate) {
            builder.setPositiveButton("Απενεργοποίηση") { _, _ ->
                confirmDeactivateSos(sos)
            }
        }

        activeDetailsDialog = builder.create()
        activeDetailsDialog?.setOnDismissListener {
            activeDetailsDialog = null
            clearActiveDialogReferences()
        }
        activeDetailsDialog?.show()
    }

    private fun confirmRespondToSos(sos: SosRecord) {
        val responderUid = getCurrentUserUid()
        if (responderUid.isNullOrBlank()) {
            Toast.makeText(context, "Πρέπει πρώτα να συνδεθείς.", Toast.LENGTH_SHORT).show()
            return
        }

        if (!responderTrackingSettings.enabled) {
            Toast.makeText(
                context,
                "Η παρακολούθηση responder είναι απενεργοποιημένη από τις ρυθμίσεις.",
                Toast.LENGTH_LONG
            ).show()
        }

        AlertDialog.Builder(context)
            .setTitle("Κατευθύνομαι")
            .setMessage(buildRespondDialogMessage())
            .setPositiveButton("Ναι") { _, _ ->
                sosRepository.respondToSos(
                    sosId = sos.id,
                    responderUid = responderUid,
                    responderEmail = getCurrentUserEmail(),
                    responderRole = getCurrentUserRole(),
                    currentLocation = getCurrentUserLocation(),
                    onSuccess = {
                        Toast.makeText(
                            context,
                            "Καταγράφηκε ότι κατευθύνεσαι προς το SOS.",
                            Toast.LENGTH_SHORT
                        ).show()

                        if (responderTrackingSettings.enabled) {
                            responderTracker.startTracking(sos.id, responderUid)
                        }

                        openGoogleNavigation(sos)
                    },
                    onError = { error ->
                        Toast.makeText(
                            context,
                            "Αποτυχία καταχώρησης ανταπόκρισης: $error",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
            .setNegativeButton("Όχι", null)
            .show()
    }

    private fun buildRespondDialogMessage(): String {
        val routingModeText = when (notificationRoutingSettings.mode.trim().uppercase(Locale.ROOT)) {
            "CERTIFIED_ONLY" -> "Ρύθμιση ειδοποίησης: μόνο εκπαιδευμένοι."
            "ALL_IN_RADIUS" -> "Ρύθμιση ειδοποίησης: όλοι όσοι βρίσκονται στην ακτίνα."
            else -> "Ρύθμιση ειδοποίησης: προσαρμοσμένη."
        }

        return "Θέλεις να καταγραφεί ότι κατευθύνεσαι προς αυτό το SOS και να ανοίξει πλοήγηση;\n\n$routingModeText"
    }

    private fun markArrived(sos: SosRecord) {
        val responderUid = getCurrentUserUid()
        if (responderUid.isNullOrBlank()) {
            Toast.makeText(context, "Δεν υπάρχει συνδεδεμένος χρήστης.", Toast.LENGTH_SHORT)
                .show()
            return
        }

        sosRepository.updateResponderStatus(
            sosId = sos.id,
            responderUid = responderUid,
            newStatus = RESPONDER_STATUS_ARRIVED,
            onSuccess = {
                responderTracker.stopTracking()
                Toast.makeText(context, "Δηλώθηκε άφιξη στο σημείο.", Toast.LENGTH_SHORT).show()
            },
            onError = { error ->
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun cancelResponding(sos: SosRecord) {
        val responderUid = getCurrentUserUid()
        if (responderUid.isNullOrBlank()) {
            Toast.makeText(context, "Δεν υπάρχει συνδεδεμένος χρήστης.", Toast.LENGTH_SHORT)
                .show()
            return
        }

        sosRepository.updateResponderStatus(
            sosId = sos.id,
            responderUid = responderUid,
            newStatus = RESPONDER_STATUS_CANCELLED,
            onSuccess = {
                responderTracker.stopTracking()
                Toast.makeText(context, "Ακύρωσες την ανταπόκριση.", Toast.LENGTH_SHORT).show()
            },
            onError = { error ->
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun openGoogleNavigation(sos: SosRecord) {
        val uri = Uri.parse("google.navigation:q=${sos.latitude},${sos.longitude}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallbackUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=${sos.latitude},${sos.longitude}"
            )
            val fallbackIntent = Intent(Intent.ACTION_VIEW, fallbackUri)
            context.startActivity(fallbackIntent)
        }
    }

    private fun confirmDeactivateSos(sos: SosRecord) {
        val actorUid = getCurrentUserUid()
        if (actorUid.isNullOrBlank()) {
            Toast.makeText(context, "Δεν υπάρχει συνδεδεμένος χρήστης.", Toast.LENGTH_SHORT)
                .show()
            return
        }

        if (!canDeactivate(sos)) {
            Toast.makeText(
                context,
                "Μόνο ο δημιουργός ή ένας διαχειριστής μπορεί να απενεργοποιήσει το SOS.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        AlertDialog.Builder(context)
            .setTitle("Απενεργοποίηση SOS")
            .setMessage("Θέλεις να απενεργοποιήσεις αυτό το SOS;")
            .setPositiveButton("Ναι") { _, _ ->
                sosRepository.deactivateSos(
                    sosId = sos.id,
                    actorUid = actorUid,
                    actorRole = getCurrentUserRole(),
                    onSuccess = {
                        responderTracker.stopTracking()
                        Toast.makeText(context, "Το SOS απενεργοποιήθηκε.", Toast.LENGTH_SHORT)
                            .show()
                    },
                    onError = { error ->
                        Toast.makeText(
                            context,
                            "Αποτυχία απενεργοποίησης SOS: $error",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
            .setNegativeButton("Όχι", null)
            .show()
    }

    private fun enforceTrackingConsistency() {
        if (!responderTracker.isCurrentlyTracking()) return

        val trackedSosId = responderTracker.getCurrentTrackedSosId()
        if (trackedSosId.isNullOrBlank()) {
            responderTracker.stopTracking()
            return
        }

        val trackedSos = currentSos.firstOrNull { it.id == trackedSosId }
        if (trackedSos == null) {
            responderTracker.stopTracking()
            return
        }

        val sosStatus = trackedSos.status.trim().uppercase(Locale.ROOT)
        if (sosStatus != STATUS_ACTIVE) {
            responderTracker.stopTracking()
            return
        }

        val currentUid = getCurrentUserUid()
        if (currentUid.isNullOrBlank()) {
            responderTracker.stopTracking()
            return
        }

        val myResponder = trackedSos.responders[currentUid]
        val myStatus = myResponder?.status?.trim()?.uppercase(Locale.ROOT)

        if (
            myResponder == null ||
            myStatus == RESPONDER_STATUS_ARRIVED ||
            myStatus == RESPONDER_STATUS_CANCELLED
        ) {
            responderTracker.stopTracking()
        }
    }

    private fun canDeactivate(sos: SosRecord): Boolean {
        val currentUid = getCurrentUserUid()
        val currentRole = getCurrentUserRole().trim().lowercase(Locale.ROOT)

        if (currentRole == ROLE_ADMIN) return true
        if (currentUid.isNullOrBlank()) return false

        return sos.createdByUid == currentUid
    }

    private fun copyCoordinatesToClipboard(sos: SosRecord) {
        val coords = String.format(Locale.US, "%.6f, %.6f", sos.latitude, sos.longitude)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SOS Coordinates", coords)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Οι συντεταγμένες αντιγράφηκαν.", Toast.LENGTH_SHORT).show()
    }

    private fun renderResponders() {
        val mapView = mapController.getMapView()
        val now = System.currentTimeMillis()
        val validKeys = mutableSetOf<String>()

        currentSos.forEach { sos ->
            sos.responders.forEach { (responderUid, responder) ->
                if (!shouldRenderResponder(responder, now)) return@forEach

                val key = buildResponderMarkerKey(sos.id, responderUid)
                validKeys.add(key)

                val geoPoint = GeoPoint(responder.latitude, responder.longitude)
                val title = getResponderDisplayName(responder)
                val snippet = buildResponderSnippet(sos, responder)
                val iconRes = when (responder.status.trim().uppercase(Locale.ROOT)) {
                    RESPONDER_STATUS_ARRIVED -> android.R.drawable.presence_online
                    RESPONDER_STATUS_CANCELLED -> android.R.drawable.presence_busy
                    else -> android.R.drawable.ic_media_play
                }
                val responderIcon = ContextCompat.getDrawable(context, iconRes)

                val existingMarker = responderMarkersByKey[key]
                if (existingMarker == null) {
                    val marker = Marker(mapView).apply {
                        position = geoPoint
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        this.title = title
                        this.snippet = snippet
                        icon = responderIcon?.constantState?.newDrawable()?.mutate()
                    }

                    responderMarkersByKey[key] = marker
                    mapView.overlays.add(marker)
                } else {
                    existingMarker.position = geoPoint
                    existingMarker.title = title
                    existingMarker.snippet = snippet
                    existingMarker.icon = responderIcon?.constantState?.newDrawable()?.mutate()
                }
            }
        }

        removeObsoleteResponderMarkers(validKeys)
    }

    private fun shouldRenderResponder(responder: Responder, now: Long): Boolean {
        if (responder.latitude == 0.0 && responder.longitude == 0.0) return false

        val status = responder.status.trim().uppercase(Locale.ROOT)

        if (!responderTrackingSettings.showCancelledResponders &&
            status == RESPONDER_STATUS_CANCELLED
        ) return false

        if (!responderTrackingSettings.showArrivedResponders &&
            status == RESPONDER_STATUS_ARRIVED
        ) return false

        val ageMs = now - responder.lastUpdatedAt
        if (responder.lastUpdatedAt > 0L &&
            ageMs > responderTrackingSettings.staleTimeoutMs
        ) return false

        return true
    }

    private fun buildResponderMarkerKey(sosId: String, responderUid: String): String {
        return "$sosId|$responderUid"
    }

    private fun removeObsoleteResponderMarkers(validKeys: Set<String>) {
        val mapView = mapController.getMapView()
        val iterator = responderMarkersByKey.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in validKeys) {
                entry.value.closeInfoWindow()
                mapView.overlays.remove(entry.value)
                iterator.remove()
            }
        }
    }

    private fun getResponderDisplayName(responder: Responder): String {
        return when {
            responder.fullName.isNotBlank() -> responder.fullName
            responder.email.isNotBlank() -> responder.email
            else -> "Responder"
        }
    }

    private fun buildResponderSnippet(sos: SosRecord, responder: Responder): String {
        val statusText = responder.status.ifBlank { RESPONDER_STATUS_RESPONDING }
        val targetText = sos.type.ifBlank { "SOS" }

        return buildString {
            append("Κατάσταση: $statusText")
            append("\n")
            append("Προς: $targetText")
            append("\n")
            append("Τελ. ενημέρωση: ${formatTimestamp(responder.lastUpdatedAt)}")
        }
    }

    private fun buildSosSnippet(sos: SosRecord): String {
        val parts = mutableListOf<String>()
        parts.add(formatTimestamp(sos.createdAt))

        val creatorDisplay = getCreatorDisplayName(sos)
        if (creatorDisplay.isNotBlank()) {
            parts.add("Δημιουργός: $creatorDisplay")
        }

        if (sos.message.isNotBlank()) {
            parts.add(sos.message)
        }

        return parts.joinToString("\n")
    }

    private fun getCreatorDisplayName(sos: SosRecord): String {
        return when {
            sos.createdByName.isNotBlank() -> sos.createdByName
            sos.createdByEmail.isNotBlank() -> sos.createdByEmail
            else -> ""
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "-"
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("el", "GR"))
        return formatter.format(Date(timestamp))
    }

    private fun clearSosMarkers() {
        val mapView = mapController.getMapView()

        sosMarkers.forEach { marker ->
            marker.closeInfoWindow()
            mapView.overlays.remove(marker)
        }
        sosMarkers.clear()

        responderMarkersByKey.values.forEach { marker ->
            marker.closeInfoWindow()
            mapView.overlays.remove(marker)
        }
        responderMarkersByKey.clear()
    }
}