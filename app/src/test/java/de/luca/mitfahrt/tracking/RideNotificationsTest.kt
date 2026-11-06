package de.luca.mitfahrt.tracking

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import de.luca.mitfahrt.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RideNotificationsTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun `active ride satisfies the actual Android live update contract`() {
        val notification = RideNotifications(context).build(TrackingState(ride = DemoRide.create(now), running = true), now)
        assertTrue(notification.hasPromotableCharacteristics())
        assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertNull(notification.contentView)
        assertEquals(Notification.CATEGORY_NAVIGATION, notification.category)
    }
    @Test fun `waiting notification carries a real countdown to the reported boarding time`() {
        val r = DemoRide.create(now).copy(boarded = false, departure = DemoRide.create(now).departure.copy(estimated = now.plusSeconds(180)))
        val notification = RideNotifications(context).build(TrackingState(ride = r, running = true), now)
        assertEquals(now.plusSeconds(180).toEpochMilli(), notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertTrue(notification.hasPromotableCharacteristics())
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("3:00"))
    }
    @Test fun `ambient wifi monitoring is not promoted as a fake journey`() {
        val notification = RideNotifications(context).build(TrackingState(running = true, monitoring = true), now)
        assertFalse(notification.extras.getBoolean("android.requestPromotedOngoing"))
    }
    @Test fun `Samsung compatibility preserves the standard style and uses only own package`() {
        val extras = NowBarSupport.notificationExtras(context.packageName, "samsung", 37, true, true)
        assertTrue(extras.getBoolean("android.requestPromotedOngoing"))
        assertTrue(extras.getBoolean("android.ongoingActivityNoti.automation"))
        assertEquals(context.packageName, extras.getString("android.ongoingActivityNoti.automationPackage"))
        assertFalse(extras.containsKey("android.ongoingActivityNoti.style"))
    }
    @Test fun `Samsung hint respects permission and the user's compatibility switch`() {
        assertFalse(NowBarSupport.notificationExtras(context.packageName, "samsung", 37, false, true).containsKey("android.ongoingActivityNoti.automation"))
        assertFalse(NowBarSupport.notificationExtras(context.packageName, "samsung", 37, true, false).containsKey("android.ongoingActivityNoti.automation"))
        assertFalse(NowBarSupport.notificationExtras(context.packageName, "google", 37, true, true).containsKey("android.ongoingActivityNoti.automation"))
    }
    @Test fun `Samsung own title provides journey text on Android 17 only`() {
        val extras = NowBarSupport.notificationExtras(context.packageName, "samsung", 37, true, true,
            true, "006 → Rheydt Hbf", "Nächster Halt: Marienplatz", "006 · 3 Min", 30)
        assertEquals(1, extras.getInt("android.ongoingActivityNoti.style"))
        assertEquals("Mitfahrt · 006 → Rheydt Hbf", extras.getString("android.ongoingActivityNoti.primaryInfo"))
        assertEquals("Mitfahrt · 006 → Rheydt Hbf", extras.getString("android.ongoingActivityNoti.nowbarPrimaryInfo"))
        assertEquals("Nächster Halt: Marienplatz", extras.getString("android.ongoingActivityNoti.secondaryInfo"))
        assertEquals("006 · 3 Min", extras.getString("android.ongoingActivityNoti.chipExpandedText"))
        assertEquals(30, extras.getInt("android.ongoingActivityNoti.progress"))
        assertEquals(context.packageName, extras.getString("android.ongoingActivityNoti.automationPackage"))
        // Never switch the working Android-16 / One-UI-8.5 notification into the card lane.
        assertFalse(NowBarSupport.notificationExtras(context.packageName, "samsung", 36, true, true, true)
            .containsKey("android.ongoingActivityNoti.style"))
    }
    @Test fun `own title respects permission vendor and both switches`() {
        listOf(
            NowBarSupport.notificationExtras(context.packageName, "samsung", 37, false, true, true),
            NowBarSupport.notificationExtras(context.packageName, "samsung", 37, true, false, true),
            NowBarSupport.notificationExtras(context.packageName, "samsung", 37, true, true, false),
            NowBarSupport.notificationExtras(context.packageName, "google", 37, true, true, true)
        ).forEach { assertFalse(it.containsKey("android.ongoingActivityNoti.style")) }
    }
    @Test fun `live update settings use the actual Android action`() {
        assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, NowBarSupport.PROMOTION_SETTINGS)
    }
    @Test fun `ride channel is suitable for promoted notifications`() {
        RideNotifications(context)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(RideNotifications.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }
}
