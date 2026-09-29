package com.guardianlink.common.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object LocationUtils {
    const val MIN_LATITUDE = -90.0
    const val MAX_LATITUDE = 90.0
    const val MIN_LONGITUDE = -180.0
    const val MAX_LONGITUDE = 180.0

    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (latitude !in MIN_LATITUDE..MAX_LATITUDE) return false
        if (longitude !in MIN_LONGITUDE..MAX_LONGITUDE) return false
        return true
    }

    fun clampLocationIntervalMs(intervalMs: Long): Long {
        val minMs = 15_000L
        val maxMs = 300_000L
        return intervalMs.coerceIn(minMs, maxMs)
    }

    fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        if (!isValidCoordinate(lat1, lon1) || !isValidCoordinate(lat2, lon2)) {
            return Double.POSITIVE_INFINITY
        }

        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2.0) +
            cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1.0 - a))
        return earthRadiusMeters * c
    }

    data class GeofenceTransition(
        val inside: Boolean,
        val entering: Boolean,
        val exiting: Boolean,
        val staying: Boolean
    )

    fun geofenceTransition(
        latitude: Double,
        longitude: Double,
        geofenceLatitude: Double,
        geofenceLongitude: Double,
        radiusMeters: Float,
        wasInside: Boolean
    ): GeofenceTransition {
        if (!isValidCoordinate(latitude, longitude) ||
            !isValidCoordinate(geofenceLatitude, geofenceLongitude) ||
            radiusMeters <= 0f)
            return GeofenceTransition(false, false, false, false)

        val distanceMeters = haversineMeters(latitude, longitude, geofenceLatitude, geofenceLongitude)
        val inside = distanceMeters <= radiusMeters.toDouble()
        return GeofenceTransition(
            inside = inside,
            entering = inside && !wasInside,
            exiting = !inside && wasInside,
            staying = inside && wasInside
        )
    }
}
