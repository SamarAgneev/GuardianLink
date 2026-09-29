// child-app/src/main/java/com/guardianlink/child/service/LocationService.kt
package com.guardianlink.child.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest
import com.google.firebase.Timestamp
import com.guardianlink.child.R
import com.guardianlink.child.firebase.ChildFirebaseManager
import com.guardianlink.common.constants.NotificationChannels
import com.guardianlink.common.constants.NotificationIds
import com.guardianlink.common.model.AlertType
import com.guardianlink.common.model.GeoFence
import com.guardianlink.common.model.LocationData
import com.guardianlink.common.security.SecurePreferences
import com.guardianlink.common.util.LocationUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class LocationService : LifecycleService() {

    companion object {
        const val ACTION_START = "guardianlink.action.START_LOCATION"
        const val ACTION_STOP  = "guardianlink.action.STOP_LOCATION"
        const val EXTRA_INTERVAL = "interval_ms"

        fun startIntent(context: Context, intervalMs: Long = 10_000L) =
            Intent(context, LocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INTERVAL, intervalMs)
            }
    }

    @Inject lateinit var firebaseManager: ChildFirebaseManager
    @Inject lateinit var securePrefs: SecurePreferences

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var activeGeofences: List<GeoFence> = emptyList()
    private val geofenceInsideState = mutableSetOf<String>()
    private var lastLocationUpdateMs: Long = 0L
    private var lastLocationLat: Double = Double.NaN
    private var lastLocationLng: Double = Double.NaN

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundNotification()
        loadGeofences()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopTracking(); stopSelf() }
            else -> {
                val interval = intent?.getLongExtra(EXTRA_INTERVAL, 10_000L) ?: 10_000L
                startTracking(interval)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
    }

    // ── Location Tracking ─────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startTracking(intervalMs: Long) {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isBlank()) {
            Timber.w("Location tracking cannot start without a device id")
            return
        }

        val permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (permission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Timber.w("Location tracking skipped: fine location permission missing")
            return
        }

        if (!isLocationEnabled()) {
            Timber.w("Location tracking skipped: GPS/location services disabled")
            return
        }

        val safeIntervalMs = LocationUtils.clampLocationIntervalMs(intervalMs)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, safeIntervalMs)
            .setMinUpdateDistanceMeters(10f)
            .setMaxUpdateDelayMillis(safeIntervalMs * 2)
            .setWaitForAccurateLocation(true)
            .build()

        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        handleLocationUpdate(location)
                    }
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request, locationCallback!!, Looper.getMainLooper()
        )
        Timber.i("Location tracking started (interval=${safeIntervalMs}ms)")
    }

    private fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    private suspend fun handleLocationUpdate(location: android.location.Location) {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isBlank()) {
            Timber.w("Location update ignored: device id missing")
            return
        }

        if (!LocationUtils.isValidCoordinate(location.latitude, location.longitude)) {
            Timber.w("Location update ignored: invalid coordinates ${location.latitude}, ${location.longitude}")
            return
        }

        val nowMs = System.currentTimeMillis()
        val lastLocationAgeMs = nowMs - location.time
        if (location.time <= 0L || lastLocationAgeMs > 5 * 60_000L) {
            Timber.w("Location update ignored: stale location age=${lastLocationAgeMs}ms")
            return
        }

        val minimalDistance = 15.0
        if (!lastLocationLat.isNaN() && !lastLocationLng.isNaN()) {
            val delta = LocationUtils.haversineMeters(location.latitude, location.longitude, lastLocationLat, lastLocationLng)
            if (delta < minimalDistance && nowMs - lastLocationUpdateMs < 10_000L) {
                return
            }
        }

        val address = try {
            if (!LocationUtils.isValidCoordinate(location.latitude, location.longitude)) "" else {
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                addresses?.firstOrNull()?.let { addr ->
                    buildString {
                        addr.thoroughfare?.let { append("$it, ") }
                        addr.locality?.let { append("$it, ") }
                        addr.adminArea?.let { append(it) }
                    }
                } ?: ""
            }
        } catch (e: Exception) {
            Timber.w(e, "Reverse geocoding failed")
            ""
        }

        val data = LocationData(
            deviceId = deviceId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.coerceAtLeast(1f),
            speed = location.speed.coerceAtLeast(0f),
            bearing = location.bearing,
            altitude = location.altitude,
            address = address,
            timestamp = Timestamp(Date(location.time)),
            batteryLevel = getBatteryLevel()
        )

        firebaseManager.pushLocation(deviceId, data)
        checkGeofences(deviceId, location.latitude, location.longitude)

        lastLocationUpdateMs = nowMs
        lastLocationLat = location.latitude
        lastLocationLng = location.longitude
    }

    // ── Geofencing ────────────────────────────────────────────────────────────

    private fun loadGeofences() {
        val deviceId = securePrefs.getString(SecurePreferences.KEY_DEVICE_ID)
        lifecycleScope.launch(Dispatchers.IO) {
            activeGeofences = firebaseManager.getGeofences(deviceId)
        }
    }

    private suspend fun checkGeofences(deviceId: String, lat: Double, lng: Double) {
        activeGeofences.filter { it.isActive }.forEach { fence ->
            val wasInside = geofenceInsideState.contains(fence.id)
            val transition = LocationUtils.geofenceTransition(
                latitude = lat,
                longitude = lng,
                geofenceLatitude = fence.latitude,
                geofenceLongitude = fence.longitude,
                radiusMeters = fence.radiusMeters,
                wasInside = wasInside
            )

            when {
                transition.entering -> {
                    geofenceInsideState.add(fence.id)
                    if (fence.alertOnEnter) {
                        firebaseManager.pushAlert(
                            deviceId = deviceId,
                            type = AlertType.GEOFENCE_ENTER,
                            title = "Entered: ${fence.name}",
                            message = "Child has entered the geofence area \"${fence.name}\""
                        )
                    }
                }
                transition.exiting -> {
                    geofenceInsideState.remove(fence.id)
                    if (fence.alertOnExit) {
                        firebaseManager.pushAlert(
                            deviceId = deviceId,
                            type = AlertType.GEOFENCE_EXIT,
                            title = "Left: ${fence.name}",
                            message = "Child has left the geofence area \"${fence.name}\""
                        )
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(android.os.BatteryManager::class.java)
        return bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(android.location.LocationManager::class.java)
        return locationManager != null && (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER))
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, NotificationChannels.MONITORING_SERVICE)
            .setContentTitle("Location Tracking")
            .setContentText("GuardianLink is tracking location with foreground permission")
            .setSmallIcon(R.drawable.ic_location)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

        startForeground(NotificationIds.FOREGROUND_SERVICE + 1, notification)
    }
}
