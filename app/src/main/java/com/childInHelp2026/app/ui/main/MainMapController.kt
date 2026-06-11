package com.childInHelp2026.app.ui.main

import android.content.Context
import android.location.Location
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainMapController(
    private val context: Context,
    private val mapView: MapView,
    private val statusView: TextView
) {

    companion object {
        private val DEFAULT_CENTER = GeoPoint(40.3000, 21.7900)
        private const val DEFAULT_ZOOM = 13.5
        private const val USER_ZOOM = 16.5
    }

    private var userMarker: Marker? = null
    private var currentUserPoint: GeoPoint? = null
    private var centeredMap = false
    private var adminLongPressListener: ((GeoPoint) -> Unit)? = null

    init {
        Configuration.getInstance().load(
            context.applicationContext,
            context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = context.packageName

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.controller.setCenter(DEFAULT_CENTER)
        statusView.text = "Φόρτωση χάρτη..."

        mapView.setOnLongClickListener {
            val point = mapView.mapCenter as? GeoPoint ?: return@setOnLongClickListener true
            adminLongPressListener?.invoke(point)
            true
        }
    }

    fun setOnAdminLongPress(listener: (GeoPoint) -> Unit) {
        adminLongPressListener = listener
    }

    fun updateUserLocation(location: Location) {
        currentUserPoint = GeoPoint(location.latitude, location.longitude)

        if (userMarker == null) {
            userMarker = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Η θέση μου"
                snippet = "Τρέχουσα θέση χρήστη"
            }
            mapView.overlays.add(userMarker)
        }

        userMarker?.position = currentUserPoint
        mapView.invalidate()
    }

    fun centerOnUserLocation() {
        val point = currentUserPoint ?: return
        mapView.controller.setZoom(USER_ZOOM)
        mapView.controller.setCenter(point)
        centeredMap = true
    }

    fun centerOnPoint(point: GeoPoint, zoom: Double = USER_ZOOM) {
        mapView.controller.setZoom(zoom)
        mapView.controller.setCenter(point)
        centeredMap = true
    }

    fun centerOnDefault(point: GeoPoint) {
        mapView.controller.setZoom(DEFAULT_ZOOM)
        mapView.controller.setCenter(point)
        centeredMap = true
    }

    fun hasCenteredMap(): Boolean = centeredMap

    fun resetCenteredState() {
        centeredMap = false
    }

    fun getMapView(): MapView = mapView

    fun setStatus(message: String) {
        statusView.text = message
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }
}