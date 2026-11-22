package de.luca.mitfahrt.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneHandedMapZoomTest {

    @Test
    fun draggingDownZoomsInAndDraggingUpZoomsOut() {
        val density = 2f
        val zoomedIn = OneHandedZoomMath.nextZoom(
            startZoom = 15.0,
            deltaYPx = 220f, // 110 dp down -> +1 zoom level
            density = density,
            minZoom = 3.0,
            maxZoom = 19.0
        )
        assertEquals(16.0, zoomedIn, 0.01)

        val zoomedOut = OneHandedZoomMath.nextZoom(
            startZoom = 15.0,
            deltaYPx = -220f, // 110 dp up -> -1 zoom level
            density = density,
            minZoom = 3.0,
            maxZoom = 19.0
        )
        assertEquals(14.0, zoomedOut, 0.01)
    }

    @Test
    fun clampsZoomWithinMinAndMaxBounds() {
        val maxClamped = OneHandedZoomMath.nextZoom(
            startZoom = 18.5,
            deltaYPx = 800f,
            density = 2f,
            minZoom = 3.0,
            maxZoom = 19.0
        )
        assertEquals(19.0, maxClamped, 0.001)

        val minClamped = OneHandedZoomMath.nextZoom(
            startZoom = 3.2,
            deltaYPx = -900f,
            density = 2f,
            minZoom = 3.0,
            maxZoom = 19.0
        )
        assertEquals(3.0, minClamped, 0.001)
        assertTrue(minClamped <= maxClamped)
    }
}
