package de.luca.mitfahrt.tracking

import android.app.Application
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RideNotificationsBugfixTest {
    @Test
    fun `monitoring notification never duplicates the stop action`() {
        val context = RuntimeEnvironment.getApplication()
        val settings = AppSettingsStore(context)
        try {
            settings.set("notification_actions", "board,arrival,stop")
            val notifications = RideNotifications(context)
            val notification = notifications.build(TrackingState(ride = null, running = true, monitoring = true))
            val titles = notification.actions.orEmpty().map { it.title.toString() }
            assertEquals("Expected exactly one Beenden action", 1, titles.count { it == "Beenden" })
        } finally {
            settings.close()
        }
    }

    @Test
    fun `android 16 live chip honors tpl_nowbar_chip customization`() {
        val context = RuntimeEnvironment.getApplication()
        val settings = AppSettingsStore(context)
        try {
            settings.set("templates_enabled", "true")
            settings.set("tpl_nowbar_chip", "L{linie}")
            val notifications = RideNotifications(context)
            val ride = RouteFixtures.ride(boarded = true, fetchedAt = T0)
            val notification = notifications.build(TrackingState(ride = ride, running = true))
            assertEquals("L006", notification.shortCriticalText)
        } finally {
            settings.close()
        }
    }

    @Test
    fun `active ride attaches live map to promoted Now Bar and builds full-width Lockscreen bus map`() {
        val context = RuntimeEnvironment.getApplication()
        val settings = AppSettingsStore(context)
        try {
            val ride = RouteFixtures.ride(boarded = true, fetchedAt = T0)
            val bus = NewVehicle("2407", ride.departure.line, "Ziel", RouteFixtures.along(900.0), T0.plusSeconds(60))
            val state = TrackingState(
                ride = ride,
                running = true,
                routeShape = RouteFixtures.shape(ride),
                liveBus = LiveBusState(journeyKey = ride.departure.key, vehicle = bus, confirmed = true, fetchedAt = T0.plusSeconds(60))
            )
            RideNotifications.setMapMode(context, de.luca.mitfahrt.widgets.MapWidgetMode.BUS_FOCUS)
            val notifications = RideNotifications(context)

            val nowBar = notifications.build(state, T0.plusSeconds(60))
            assertTrue("Now Bar notification must remain promotable with live map largeIcon", nowBar.hasPromotableCharacteristics())
            assertNotNull("Expanded Now Bar should include live bus map thumbnail", nowBar.getLargeIcon())

            val lockscreenMap = notifications.buildMapNotification(state, T0.plusSeconds(60))
            assertNotNull("Lockscreen full-width live map notification should be created", lockscreenMap)
            assertEquals(android.app.Notification.VISIBILITY_PUBLIC, lockscreenMap!!.visibility)
            assertEquals(android.app.Notification.BigPictureStyle::class.java.name, lockscreenMap.extras.getString(android.app.Notification.EXTRA_TEMPLATE))
            assertEquals(listOf("Strecke", "Vollbild"), lockscreenMap.actions.map { it.title.toString() })

            assertEquals(de.luca.mitfahrt.widgets.MapWidgetMode.OVERVIEW, RideNotifications.toggleMapMode(context))
            val overviewMap = notifications.buildMapNotification(state, T0.plusSeconds(60))!!
            assertEquals(listOf("Bus fokussieren", "Vollbild"), overviewMap.actions.map { it.title.toString() })

            settings.set("notification_map", "false")
            assertNull("Disabling notification_map hides lockscreen map card", notifications.buildMapNotification(state, T0.plusSeconds(60)))
        } finally {
            settings.close()
        }
    }

    @Test
    fun `watch face map mode defaults to BUS_FOCUS and toggles to OVERVIEW`() {
        val context = RuntimeEnvironment.getApplication()
        de.luca.mitfahrt.watch.WatchFaceMapPublisher.setMode(context, de.luca.mitfahrt.widgets.MapWidgetMode.BUS_FOCUS)
        assertEquals("focus", de.luca.mitfahrt.watch.WatchFaceMapPublisher.modeName(context))
        assertEquals(
            de.luca.mitfahrt.widgets.MapWidgetMode.OVERVIEW,
            de.luca.mitfahrt.watch.WatchFaceMapPublisher.toggleMode(context)
        )
        assertEquals("overview", de.luca.mitfahrt.watch.WatchFaceMapPublisher.modeName(context))
    }
}
