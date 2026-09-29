package com.guardianlink.common

import com.guardianlink.common.util.LocationUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationUtilsTest {

    @Test
    fun haversineDistanceSamePointIsZero() {
        val distance = LocationUtils.haversineMeters(51.5074, -0.1278, 51.5074, -0.1278)
        assertEquals(0.0, distance, 0.01)
    }

    @Test
    fun haversineDistanceMatchesExpectedEarthDistance() {
        val distance = LocationUtils.haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertTrue(distance > 110_000.0)
        assertTrue(distance < 112_000.0)
    }

    @Test
    fun geofenceTransitionDetectsEnteringAndStaying() {
        val entering = LocationUtils.geofenceTransition(
            latitude = 0.0,
            longitude = 0.0,
            geofenceLatitude = 0.0,
            geofenceLongitude = 0.0,
            radiusMeters = 100f,
            wasInside = false
        )
        assertTrue(entering.entering)
        assertFalse(entering.exiting)

        val staying = LocationUtils.geofenceTransition(
            latitude = 0.0,
            longitude = 0.0,
            geofenceLatitude = 0.0,
            geofenceLongitude = 0.0,
            radiusMeters = 100f,
            wasInside = true
        )
        assertTrue(staying.staying)
        assertFalse(staying.entering)
    }

    @Test
    fun geofenceTransitionDetectsExiting() {
        val exiting = LocationUtils.geofenceTransition(
            latitude = 0.5,
            longitude = 0.0,
            geofenceLatitude = 0.0,
            geofenceLongitude = 0.0,
            radiusMeters = 100f,
            wasInside = true
        )
        assertTrue(exiting.exiting)
        assertFalse(exiting.staying)
    }

    @Test
    fun invalidCoordinatesAreRejected() {
        assertFalse(LocationUtils.isValidCoordinate(91.0, 0.0))
        assertFalse(LocationUtils.isValidCoordinate(0.0, 181.0))
        assertFalse(LocationUtils.isValidCoordinate(Double.NaN, 0.0))
    }
}
