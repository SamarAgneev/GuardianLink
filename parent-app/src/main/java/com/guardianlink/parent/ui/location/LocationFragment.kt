// parent-app/src/main/java/com/guardianlink/parent/ui/location/LocationFragment.kt
package com.guardianlink.parent.ui.location

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.guardianlink.common.model.GeoFence
import com.guardianlink.common.model.LocationData
import com.guardianlink.parent.R
import com.guardianlink.parent.ui.dashboard.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LocationFragment : Fragment(R.layout.fragment_location), OnMapReadyCallback {

    private val viewModel: DashboardViewModel by activityViewModels()

    private var googleMap: GoogleMap? = null
    private var childMarker: Marker? = null
    private val geofenceCircles = mutableListOf<Circle>()

    private lateinit var tvAddress: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var btnHistory: Button
    private lateinit var btnAddGeofence: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupMap()
        observeLocation()
    }

    private fun bindViews(view: View) {
        tvAddress      = view.findViewById(R.id.tv_address)
        tvCoords       = view.findViewById(R.id.tv_coordinates)
        tvAccuracy     = view.findViewById(R.id.tv_accuracy)
        tvSpeed        = view.findViewById(R.id.tv_speed)
        btnHistory     = view.findViewById(R.id.btn_location_history)
        btnAddGeofence = view.findViewById(R.id.btn_add_geofence)

        btnAddGeofence.setOnClickListener { showAddGeofenceDialog() }
        btnHistory.setOnClickListener     { showLocationHistory() }
    }

    private fun setupMap() {
        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.apply {
            isZoomControlsEnabled   = true
            isCompassEnabled        = true
            isMyLocationButtonEnabled = false
        }
        map.mapType = GoogleMap.MAP_TYPE_NORMAL
    }

    private fun observeLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.liveLocation.collect { location ->
                    location ?: return@collect
                    updateLocationUI(location)
                    updateMapMarker(location)
                }
            }
        }
    }

    private fun updateLocationUI(location: LocationData) {
        tvAddress.text  = location.address.ifBlank { "Address unknown" }
        tvCoords.text   = "%.6f, %.6f".format(location.latitude, location.longitude)
        tvAccuracy.text = "Accuracy: ±${location.accuracy.toInt()}m"
        tvSpeed.text    = "Speed: ${"%.1f".format(location.speed * 3.6)} km/h"
    }

    private fun updateMapMarker(location: LocationData) {
        val map = googleMap ?: return
        val latLng = LatLng(location.latitude, location.longitude)

        if (childMarker == null) {
            childMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Child's Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(latLng, 15f)
            )
        } else {
            childMarker!!.position = latLng
            map.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        }

        // Draw accuracy circle
        map.clear()
        childMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Child's Location")
                .snippet(location.address)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        map.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(location.accuracy.toDouble())
                .strokeColor(Color.parseColor("#4D2196F3"))
                .fillColor(Color.parseColor("#1A2196F3"))
                .strokeWidth(2f)
        )

        redrawGeofences()
    }

    // ── Geofences ─────────────────────────────────────────────────────────────

    private fun addGeofence(geofence: GeoFence) {
        val map = googleMap ?: return
        val center = LatLng(geofence.latitude, geofence.longitude)

        val circle = map.addCircle(
            CircleOptions()
                .center(center)
                .radius(geofence.radiusMeters.toDouble())
                .strokeColor(Color.parseColor("#CC4CAF50"))
                .fillColor(Color.parseColor("#334CAF50"))
                .strokeWidth(3f)
        )
        geofenceCircles.add(circle)

        map.addMarker(
            MarkerOptions()
                .position(center)
                .title(geofence.name)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
    }

    private fun redrawGeofences() {
        geofenceCircles.forEach { it.remove() }
        geofenceCircles.clear()
        // Reload and redraw from settings
        viewModel.settings.value?.geofences?.forEach { addGeofence(it) }
    }

    private fun showAddGeofenceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_geofence, null)
        val etName     = dialogView.findViewById<EditText>(R.id.et_geofence_name)
        val etRadius   = dialogView.findViewById<EditText>(R.id.et_geofence_radius)

        // Use last known location as center (or let user pick on map)
        val currentLoc = viewModel.liveLocation.value

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Add Geofence")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name   = etName.text.toString().trim()
                val radius = etRadius.text.toString().toFloatOrNull() ?: 200f

                if (name.isBlank()) {
                    Toast.makeText(requireContext(), "Enter a name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (currentLoc == null) {
                    Toast.makeText(requireContext(), "No location available", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val fence = GeoFence(
                    id           = java.util.UUID.randomUUID().toString(),
                    name         = name,
                    latitude     = currentLoc.latitude,
                    longitude    = currentLoc.longitude,
                    radiusMeters = radius
                )

                val currentFences = viewModel.settings.value?.geofences?.toMutableList()
                    ?: mutableListOf()
                currentFences.add(fence)
                viewModel.updateSetting("geofences", currentFences)
                addGeofence(fence)
                Toast.makeText(requireContext(), "Geofence added: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLocationHistory() {
        // Navigate to location history screen or show bottom sheet
        // findNavController().navigate(R.id.locationHistoryFragment)
    }
}
