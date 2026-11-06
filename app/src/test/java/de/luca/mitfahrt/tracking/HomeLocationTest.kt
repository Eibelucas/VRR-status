package de.luca.mitfahrt.tracking

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import de.luca.mitfahrt.data.GeoPoint
import de.luca.mitfahrt.data.NearbyStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class HomeLocationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun `missing permission does not register sensors or start a service`() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        val event = HomeLocation(app).updates().first()
        assertEquals(NearbyStatus.NO_PERMISSION, event.status)
        assertTrue(shadowOf(app.getSystemService(LocationManager::class.java)).getLocationUpdateListeners().isEmpty())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun `a cached fix works without bus tracking and closing the stream removes sensor listeners`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 51.2; longitude = 6.4; accuracy = 30f; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        val event = withTimeout(2000) { HomeLocation(app).updates().first { it.status == NearbyStatus.READY } }
        assertEquals(GeoPoint(51.2, 6.4), event.point)
        assertFalse(event.approximate)
        assertTrue(shadow.getLocationUpdateListeners().isEmpty())
        assertNull(shadowOf(app).nextStartedService)
    }
}
