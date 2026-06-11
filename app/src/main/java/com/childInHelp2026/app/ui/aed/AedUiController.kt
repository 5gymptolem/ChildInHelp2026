package com.childInHelp2026.app.ui.aed

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButton
import com.childInHelp2026.app.R
import com.childInHelp2026.app.data.Aed
import com.childInHelp2026.app.ui.main.MainMapController
import org.osmdroid.bonuspack.clustering.RadiusMarkerClusterer
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale

class AedUiController(
    private val context: Context,
    private val mapController: MainMapController,
    private val getCurrentUserLocation: () -> Location?,
    private val onStatusMessage: (String) -> Unit,
) {

    private val aedMarkers = mutableListOf<Marker>()
    private var aedClusterer: RadiusMarkerClusterer? = null
    private var nearestHighlightOverlay: Polygon? = null
    private var currentAeds: List<Aed> = emptyList()

    private var pulseHandler: Handler? = null
    private var pulseRunnable: Runnable? = null

    fun updateAeds(aeds: List<Aed>, isAedTabActive: Boolean) {
        currentAeds = aeds

        if (isAedTabActive) {
            renderForActiveTab()
        } else {
            renderForInactiveTab()
        }
    }

    fun renderForActiveTab() {
        clearAedOverlays()

        val mapView = mapController.getMapView()
        val defaultMarkerIcon = ContextCompat.getDrawable(context, R.drawable.ic_aed_marker)
        val clusterer = buildClusterer()
        aedClusterer = clusterer

        val nearestAed = getNearestAed()

        currentAeds.forEach { aed ->
            val isNearest = (nearestAed != null) && (aed.id == nearestAed.id)

            val marker = Marker(mapView).apply {
                position = GeoPoint(aed.latitude, aed.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = getDisplayTitle(aed)
                snippet = buildAedSnippet(aed)

                defaultMarkerIcon?.let {
                    icon = it.constantState?.newDrawable()?.mutate() ?: it
                }

                relatedObject = if (isNearest) "nearest" else null

                setOnMarkerClickListener { _, _ ->
                    showAedDetailsDialog(aed)
                    true
                }
            }

            aedMarkers.add(marker)
            clusterer.add(marker)
        }

        mapView.overlays.add(clusterer)

        if (nearestAed != null) {
            addNearestHighlight(nearestAed)
        }

        mapView.invalidate()
        onStatusMessage(buildStatusMessage(nearestAed))

        if (!mapController.hasCenteredMap() && currentAeds.isNotEmpty() && getCurrentUserLocation() == null) {
            val first = currentAeds.first()
            mapController.centerOnDefault(GeoPoint(first.latitude, first.longitude))
        }
    }

    fun renderForInactiveTab() {
        clearAedOverlays()
        mapController.getMapView().invalidate()
    }

    fun onUserLocationUpdated() {
        if (currentAeds.isEmpty()) return

        onStatusMessage(buildStatusMessage(getNearestAed()))
        if (aedClusterer != null) {
            renderForActiveTab()
        }
    }

    fun showAedListDialog() {
        if (currentAeds.isEmpty()) {
            Toast.makeText(context, "Δεν υπάρχουν διαθέσιμοι απινιδωτές.", Toast.LENGTH_SHORT).show()
            return
        }

        val topFive = getTopNearestAeds(limit = 5)

        val items = topFive.mapIndexed { index, aed ->
            val distanceText = formatDistanceFromUser(aed)
            buildString {
                if (index == 0 && getCurrentUserLocation() != null) {
                    append("⭐ Κοντινότερος\n")
                }

                append(getDisplayTitle(aed))

                if (aed.address.isNotBlank()) {
                    append("\n")
                    append(context.getString(R.string.aed_address, aed.address))
                }

                if (aed.contactPhone.isNotBlank()) {
                    append("\n")
                    append(context.getString(R.string.aed_phone, aed.contactPhone))
                }

                if (aed.contactEmail.isNotBlank()) {
                    append("\n")
                    append(context.getString(R.string.aed_email, aed.contactEmail))
                }

                if (distanceText.isNotBlank()) {
                    append("\n")
                    append(context.getString(R.string.aed_distance, distanceText))
                }
            }
        }.toTypedArray()

        val title = if (getCurrentUserLocation() != null) {
            "5 κοντινότεροι Απινιδωτές"
        } else {
            "Λίστα Απινιδωτών"
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { _, which ->
                val selected = topFive[which]
                val point = GeoPoint(selected.latitude, selected.longitude)
                mapController.centerOnPoint(point)
                showAedDetailsDialog(selected)
            }
            .setNegativeButton("Κλείσιμο", null)
            .show()
    }

    fun getNearestAed(): Aed? {
        return getSortedAedsByDistance().firstOrNull()
    }

    fun showNearestAedDialog(): Boolean {
        val nearest = getNearestAed() ?: return false
        val point = GeoPoint(nearest.latitude, nearest.longitude)
        mapController.centerOnPoint(point)
        showAedDetailsDialog(nearest)
        return true
    }

    private fun showAedDetailsDialog(aed: Aed) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_aed_details, null)

        val nearest = getNearestAed()
        val isNearest = nearest?.id == aed.id

        val textTitle = view.findViewById<TextView>(R.id.textTitle)
        val textBadge = view.findViewById<TextView>(R.id.textBadge)
        val textLocation = view.findViewById<TextView>(R.id.textLocation)
        val textAddress = view.findViewById<TextView>(R.id.textAddress)
        val textDistance = view.findViewById<TextView>(R.id.textDistance)
        val textPhone = view.findViewById<TextView>(R.id.textPhone)
        val textEmail = view.findViewById<TextView>(R.id.textEmail)
        val btnNavigate = view.findViewById<MaterialButton>(R.id.btnNavigate)
        val btnCopy = view.findViewById<MaterialButton>(R.id.btnCopy)

        textTitle.text = "Απινιδωτής"
        textLocation.text = getDisplayTitle(aed)

        textBadge.visibility = if (isNearest && getCurrentUserLocation() != null) {
            View.VISIBLE
        } else {
            View.GONE
        }

        if (aed.address.isNotBlank()) {
            textAddress.visibility = View.VISIBLE
            textAddress.text = aed.address
        } else {
            textAddress.visibility = View.GONE
        }

        val distanceText = formatDistanceFromUser(aed)
        if (distanceText.isNotBlank()) {
            textDistance.visibility = View.VISIBLE
            textDistance.text = context.getString(R.string.aed_distance, distanceText)
        } else {
            textDistance.visibility = View.GONE
        }

        if (aed.contactPhone.isNotBlank()) {
            textPhone.visibility = View.VISIBLE
            textPhone.text = context.getString(R.string.aed_phone, aed.contactPhone)
        } else {
            textPhone.visibility = View.GONE
        }

        if (aed.contactEmail.isNotBlank()) {
            textEmail.visibility = View.VISIBLE
            textEmail.text = context.getString(R.string.aed_email, aed.contactEmail)
        } else {
            textEmail.visibility = View.GONE
        }

        btnNavigate.setOnClickListener {
            openNavigation(aed)
        }

        btnCopy.setOnClickListener {
            copyCoordinates(aed)
        }

        AlertDialog.Builder(context)
            .setView(view)
            .setNeutralButton("Κοινοποίηση") { _, _ ->
                shareAed(aed)
            }
            .setNegativeButton("Κλείσιμο", null)
            .show()
    }

    private fun openNavigation(aed: Aed) {
        val uri = "google.navigation:q=${aed.latitude},${aed.longitude}".toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(
                Intent.ACTION_VIEW,
                "geo:${aed.latitude},${aed.longitude}?q=${aed.latitude},${aed.longitude}".toUri()
            )
            context.startActivity(fallback)
        }
    }

    private fun shareAed(aed: Aed) {
        val text = buildString {
            append(getDisplayTitle(aed))

            if (aed.address.isNotBlank()) {
                append("\n${aed.address}")
            }

            if (aed.contactPhone.isNotBlank()) {
                append("\nΤηλέφωνο: ${aed.contactPhone}")
            }

            if (aed.contactEmail.isNotBlank()) {
                append("\nEmail: ${aed.contactEmail}")
            }

            append("\nΣυντεταγμένες: ${aed.latitude}, ${aed.longitude}")

            if (aed.notes.isNotBlank()) {
                append("\nΣημειώσεις: ${aed.notes}")
            }
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

        context.startActivity(Intent.createChooser(intent, "Κοινοποίηση απινιδωτή"))
    }

    private fun copyCoordinates(aed: Aed) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = "${aed.latitude}, ${aed.longitude}"
        clipboard.setPrimaryClip(ClipData.newPlainText("AED Coordinates", text))
        Toast.makeText(context, "Οι συντεταγμένες αντιγράφηκαν.", Toast.LENGTH_SHORT).show()
    }

    private fun buildAedSnippet(aed: Aed): String {
        val parts = mutableListOf<String>()

        if (aed.address.isNotBlank()) {
            parts.add(aed.address)
        }

        if (aed.contactPhone.isNotBlank()) {
            parts.add(context.getString(R.string.aed_phone, aed.contactPhone))
        }

        if (aed.contactEmail.isNotBlank()) {
            parts.add(aed.contactEmail)
        }

        if (aed.notes.isNotBlank()) {
            parts.add(aed.notes)
        }

        return if (parts.isEmpty()) "Απινιδωτής" else parts.joinToString("\n")
    }

    private fun formatDistanceFromUser(aed: Aed): String {
        val meters = getDistanceMeters(aed) ?: return ""

        return if (meters >= 1000f) {
            String.format(Locale.US, "%.2f km", meters / 1000f)
        } else {
            "${meters.toInt()} m"
        }
    }

    private fun getDistanceMeters(aed: Aed): Float? {
        val userLoc = getCurrentUserLocation() ?: return null

        val results = FloatArray(1)
        Location.distanceBetween(
            userLoc.latitude,
            userLoc.longitude,
            aed.latitude,
            aed.longitude,
            results
        )
        return results[0]
    }

    private fun getSortedAedsByDistance(): List<Aed> {
        val userLoc = getCurrentUserLocation()

        return if (userLoc == null) {
            currentAeds.sortedBy { getDisplayTitle(it).lowercase(Locale.getDefault()) }
        } else {
            currentAeds.sortedBy { getDistanceMeters(it) ?: Float.MAX_VALUE }
        }
    }

    private fun getTopNearestAeds(limit: Int): List<Aed> {
        return getSortedAedsByDistance().take(limit)
    }

    private fun buildStatusMessage(nearestAed: Aed?): String {
        if (currentAeds.isEmpty()) {
            return "Δεν βρέθηκαν ενεργοί απινιδωτές."
        }

        val nearestDistance = nearestAed?.let { formatDistanceFromUser(it) }.orEmpty()

        return if (nearestAed != null && nearestDistance.isNotBlank()) {
            "Βρέθηκαν ${currentAeds.size} ενεργοί απινιδωτές. Κοντινότερος: ${getDisplayTitle(nearestAed)} ($nearestDistance)"
        } else {
            "Βρέθηκαν ${currentAeds.size} ενεργοί απινιδωτές."
        }
    }

    private fun getDisplayTitle(aed: Aed): String {
        return when {
            aed.locationName.isNotBlank() -> aed.locationName
            else -> "AED"
        }
    }

    private fun buildClusterer(): RadiusMarkerClusterer {
        return RadiusMarkerClusterer(context).apply {
            setRadius(80)
            setMaxClusteringZoomLevel(17)
        }
    }

    private fun addNearestHighlight(aed: Aed) {
        val mapView = mapController.getMapView()
        val center = GeoPoint(aed.latitude, aed.longitude)

        val circle = Polygon().apply {
            points = Polygon.pointsAsCircle(center, 35.0)
            outlinePaint.color = 0xAAFF3B30.toInt()
            outlinePaint.strokeWidth = 4f
            fillPaint.color = 0x33FF6B6B
            title = "Κοντινότερος Απινιδωτής"
        }

        nearestHighlightOverlay = circle
        mapView.overlays.add(circle)

        startPulseAnimation(circle, center, mapView)
    }

    private fun startPulseAnimation(circle: Polygon, center: GeoPoint, mapView: MapView) {
        stopPulseAnimation()

        pulseHandler = Handler(Looper.getMainLooper())

        var growing = true
        var radius = 35.0

        pulseRunnable = object : Runnable {
            override fun run() {
                radius = if (growing) radius + 2 else radius - 2

                if (radius >= 55) growing = false
                if (radius <= 35) growing = true

                circle.points = Polygon.pointsAsCircle(center, radius)
                mapView.invalidate()

                pulseHandler?.postDelayed(this, 80)
            }
        }

        pulseHandler?.post(pulseRunnable!!)
    }

    private fun stopPulseAnimation() {
        pulseHandler?.removeCallbacksAndMessages(null)
        pulseHandler = null
        pulseRunnable = null
    }

    private fun clearAedOverlays() {
        val mapView = mapController.getMapView()

        stopPulseAnimation()

        aedClusterer?.let { cluster ->
            mapView.overlays.remove(cluster)
            cluster.onDetach(mapView)
        }
        aedClusterer = null

        nearestHighlightOverlay?.let { overlay ->
            mapView.overlays.remove(overlay)
        }
        nearestHighlightOverlay = null

        aedMarkers.forEach { mapView.overlays.remove(it) }
        aedMarkers.clear()
    }
}