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
class TripPlacesStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val school = TripPlace(PlaceKind.STOP, "de:05116:7300", "MG Bismarckplatz", point = GeoPoint(51.192, 6.442))
    private val home = TripPlace(PlaceKind.ADDRESS, "streetID:1", "Hauptstraße 10", point = GeoPoint(51.19, 6.44))

    @Before fun clear() {
        listOf(AppSettingsStore.PREFERENCES, "trip_places", "journey_companion").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun `search history is optional and never holds the own position`() {
        val store = TripPlacesStore(app)
        store.remember(TripPlace.MY_LOCATION.copy(point = GeoPoint(51.123456, 6.654321)), school)
        store.remember(home, school)
        assertEquals(2, store.state.value.recent.size)
        val raw = app.getSharedPreferences("trip_places", Context.MODE_PRIVATE).getString("state", "")!!
        assertFalse("51.123456" in raw)
        // A second instance (e.g. after a restart) reads the same history.
        assertEquals(2, TripPlacesStore(app).state.value.recent.size)
        store.removeRecent(store.state.value.recent.first().key)
        assertEquals(1, store.state.value.recent.size)

        AppSettingsStore(app).set("trip_history", "false")
        store.refresh()
        assertTrue(store.state.value.recent.isEmpty())
        store.remember(home, school)
        assertTrue(store.state.value.recent.isEmpty())
    }

    @Test fun `saved destinations are kept when the history is cleared`() {
        val store = TripPlacesStore(app)
        store.save(school, "Schule")
        store.remember(home, school)
        store.clearRecent()
        assertTrue(store.state.value.recent.isEmpty())
        assertEquals("Schule", TripPlacesStore(app).state.value.saved.single().title)
        store.clearAll()
        assertTrue(TripPlacesStore(app).state.value.saved.isEmpty())
    }

    @Test fun `history follows the retention setting`() {
        var now = Instant.parse("2026-11-10T07:00:00Z")
        AppSettingsStore(app).set("history_days", "7")
        val store = TripPlacesStore(app) { now }
        store.remember(home, school)
        now = now.plusSeconds(8L * 86_400)
        store.refresh()
        assertTrue(store.state.value.recent.isEmpty())
    }

    @Test fun `accompanied journey survives a restart and resets the started flag per leg`() {
        val journey = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), TripFixtures.T0).single()
        val store = JourneyCompanionStore(app)
        val companion = JourneyCompanions.tracking(JourneyCompanions.start(journey, TripFixtures.T0)!!, "bus")
        store.set(companion)
        store.markSeen("other")
        assertFalse(store.seen.value)
        store.markSeen("bus")
        val restored = JourneyCompanionStore(app)
        assertEquals("bus", restored.state.value!!.trackedKey)
        assertTrue(restored.seen.value)
        restored.set(JourneyCompanions.tracking(JourneyCompanions.advance(companion)!!, "train"))
        assertFalse(restored.seen.value)
        restored.clear()
        assertNull(JourneyCompanionStore(app).state.value)
    }
}
