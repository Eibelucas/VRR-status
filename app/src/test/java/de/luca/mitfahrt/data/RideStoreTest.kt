package de.luca.mitfahrt.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RideStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val now = Instant.now()

    @Before fun clearPreferences() {
        app.getSharedPreferences("ride", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun ride(trip: String = "first"): Ride {
        val planned = now.plusSeconds(60)
        val departure = Departure("line:006", "006", "Schule", "boarding", "Hauptbahnhof", trip,
            planned, planned.plusSeconds(60), "6", observedAt = now)
        return Ride(departure, listOf(
            RideStop("boarding", "Hauptbahnhof", null, planned, planned),
            RideStop("school", "Schule", null, now.plusSeconds(900), now.plusSeconds(900))
        ), 0, now, boarded = false, trackingStartedAt = now)
    }

    @Test fun `deletion survives a new store while active ride remains available`() {
        val store = RideStore(app)
        val ride = ride()
        store.setRide(ride)

        assertEquals(ride, store.forgetLastRide(ride.departure.key))
        assertNull(store.lastRide.value)
        val restarted = RideStore(app)
        assertNull(restarted.lastRide.value)
        assertEquals(ride, restarted.state.value.ride)
    }

    @Test fun `refresh and resume cannot resurrect a deleted last ride`() {
        val store = RideStore(app)
        val ride = ride()
        store.setRide(ride)
        store.forgetLastRide(ride.departure.key)
        val refreshed = ride.copy(fetchedAt = now.plusSeconds(30), gpsPassedIndex = 0)

        store.setRide(refreshed, preserveStatus = true)
        assertNull(store.lastRide.value)
        assertEquals(refreshed, store.state.value.ride)

        val restarted = RideStore(app)
        restarted.setRide(refreshed.copy(boarded = true))
        assertNull(restarted.lastRide.value)
        assertTrue(restarted.state.value.ride!!.boarded)
        assertNull(RideStore(app).lastRide.value)
    }

    @Test fun `undo restores persistently and allows later updates for that journey`() {
        val store = RideStore(app)
        val ride = ride()
        store.setRide(ride)
        val removed = store.forgetLastRide(ride.departure.key)!!

        assertTrue(store.restoreLastRide(removed))
        assertEquals(ride, store.lastRide.value)
        val restarted = RideStore(app)
        assertEquals(ride, restarted.lastRide.value)
        val refreshed = ride.copy(fetchedAt = now.plusSeconds(30), boarded = true)
        restarted.setRide(refreshed)
        assertEquals(refreshed, restarted.lastRide.value)
        assertEquals(refreshed, RideStore(app).lastRide.value)
        assertFalse(restarted.restoreLastRide(removed))
    }

    @Test fun `new genuine last ride survives an old undo action`() {
        val store = RideStore(app)
        val first = ride()
        val next = ride("next")
        store.setRide(first)
        val removed = store.forgetLastRide(first.departure.key)!!
        store.setRide(next)

        assertFalse(store.restoreLastRide(removed))
        assertEquals(next, store.lastRide.value)
        assertEquals(next, RideStore(app).lastRide.value)
        store.setRide(first.copy(fetchedAt = now.plusSeconds(30)))
        assertEquals(next, store.lastRide.value)
    }

    @Test fun `only the current last ride can be deleted`() {
        val store = RideStore(app)
        val current = ride()
        store.setRide(current)

        assertNull(store.forgetLastRide(ride("other").departure.key))
        assertEquals(current, store.lastRide.value)
        assertEquals(current, RideStore(app).lastRide.value)
        assertFalse(store.restoreLastRide(current))
    }

    @Test fun `delete and undo leave all active tracking flags untouched`() {
        val store = RideStore(app)
        val ride = ride()
        store.setRide(ride)
        store.update { it.copy(running = true, monitoring = true, wifi = WifiSignal.CONNECTED,
            location = GeoPoint(51.19, 6.44), locationAt = now, lastRefreshFailed = true,
            message = "Verbindung unterbrochen", candidates = listOf(ride.departure)) }
        val tracking = store.state.value

        val removed = store.forgetLastRide(ride.departure.key)!!
        assertEquals(tracking, store.state.value)
        assertTrue(store.restoreLastRide(removed))
        assertEquals(tracking, store.state.value)
    }

    @Test fun `older undo cannot reverse a newer deletion and other tombstones remain`() {
        val store = RideStore(app)
        val first = ride()
        val next = ride("next")
        store.setRide(first)
        val firstRemoved = store.forgetLastRide(first.departure.key)!!
        store.setRide(next)
        val nextRemoved = store.forgetLastRide(next.departure.key)!!

        assertFalse(store.restoreLastRide(firstRemoved))
        assertTrue(store.restoreLastRide(nextRemoved))
        store.setRide(first)
        assertEquals(next, store.lastRide.value)
        assertEquals(next, RideStore(app).lastRide.value)
    }

    @Test fun `clearing tracking or viewing demo does not remove deletion protection`() {
        val store = RideStore(app)
        val ride = ride()
        store.setRide(ride)
        val removed = store.forgetLastRide(ride.departure.key)!!
        store.clear()
        store.setRide(DemoRide.create(now))

        assertNull(store.lastRide.value)
        assertFalse(store.restoreLastRide(removed.copy(demo = true)))
        val restarted = RideStore(app)
        restarted.setRide(ride)
        assertNull(restarted.lastRide.value)
        assertTrue(restarted.restoreLastRide(removed))
    }
    @Test fun `undo keeps live changes made while the saved ride was hidden`() {
        val store = RideStore(app)
        val original = ride()
        store.setRide(original)
        val removed = store.forgetLastRide(original.departure.key)!!
        val refreshed = original.copy(fetchedAt = now.plusSeconds(30), boarded = true, gpsPassedIndex = 0)
        store.setRide(refreshed)
        assertTrue(store.restoreLastRide(removed))
        assertEquals(refreshed, store.lastRide.value)
        assertEquals(refreshed, RideStore(app).lastRide.value)
        assertEquals(refreshed, store.state.value.ride)
    }

    @Test fun `public vehicle telemetry survives a same journey refresh but is never persisted`() {
        val store = RideStore(app)
        val original = ride()
        store.setRide(original)
        val vehicle = NewVehicle("2114", "006", "Schule", GeoPoint(51.18, 6.4), now)
        val telemetry = LiveBusState(journeyKey = original.departure.key, revision = 4, vehicle = vehicle,
            selectedVehicleId = vehicle.id, fetchedAt = now)
        store.update { it.copy(liveBus = telemetry) }
        store.setRide(original.copy(fetchedAt = now.plusSeconds(15)), preserveStatus = true)
        assertEquals(telemetry, store.state.value.liveBus)
        assertNull(RideStore(app).state.value.liveBus.vehicle)
        assertFalse(app.getSharedPreferences("ride", Context.MODE_PRIVATE).getString("active", "")!!.contains("2114"))
    }
    @Test fun `switching journeys clears vehicle selection and boarding preserves it with a new revision`() {
        val store = RideStore(app)
        val original = ride()
        store.setRide(original)
        store.update { it.copy(liveBus = LiveBusState(original.departure.key, revision = 2, selectedVehicleId = "2114")) }
        store.setRide(ride("another"))
        assertNull(store.state.value.liveBus.selectedVehicleId)
        assertEquals(ride("another").departure.key, store.state.value.liveBus.journeyKey)
        store.update { it.copy(liveBus = it.liveBus.copy(selectedVehicleId = "2114")) }
        val previousRevision = store.state.value.liveBus.revision
        store.setRide(ride("another").copy(boarded = true))
        assertEquals("2114", store.state.value.liveBus.selectedVehicleId)
        assertEquals(previousRevision + 1, store.state.value.liveBus.revision)
        store.clear()
        assertEquals(LiveBusState(), store.state.value.liveBus)
    }

    @Test fun `store updates never create archive entries and never resurrect deleted journeys`() {
        val directory = kotlin.io.path.createTempDirectory("archive-integration-").toFile()
        try {
            val archive = JourneyArchiveStore(java.io.File(directory, "archive.json"))
            val store = RideStore(app, onRideUpdated = archive::update)
            val selected = ride()
            store.setRide(selected)
            assertTrue(archive.state.value.entries.isEmpty())
            archive.record(selected) // Explicit successful tracking start is the sole creation step.
            store.setRide(selected.copy(boarded = true, fetchedAt = now.plusSeconds(15)))
            assertEquals(1, archive.state.value.entries.size)
            assertTrue(archive.state.value.entries.single().ride.boarded)
            val removed = archive.remove(setOf(JourneyArchive.id(selected)))!!
            store.setRide(selected.copy(boarded = true, fetchedAt = now.plusSeconds(30)))
            assertTrue(archive.state.value.entries.isEmpty())
            assertEquals(1, archive.restore(removed))
        } finally { directory.deleteRecursively() }
    }

}
