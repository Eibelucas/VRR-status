package de.luca.mitfahrt.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AlertPreferencesStoreTest {
    @Test fun `running service instance reads changes made in settings and new instances restore them`() {
        val app: Application = RuntimeEnvironment.getApplication()
        val service = AlertPreferencesStore(app)
        val settings = AlertPreferencesStore(app)
        settings.setArrivalEnabled(false)
        settings.setExitEnabled(false)
        settings.setLeadSeconds(180)
        val expected = AlertPreferences(false, false, 180)
        assertEquals(expected, settings.state.value)
        assertEquals(expected, service.current())
        assertEquals(expected, AlertPreferencesStore(app).state.value)
    }
    @Test fun `unknown stored timing recovers to the documented default`() {
        val app: Application = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("journey_alert_options", Context.MODE_PRIVATE).edit().putInt("lead_seconds", 999).apply()
        assertEquals(60, AlertPreferencesStore(app).current().leadSeconds)
    }

    @Test fun `customization screen changes update existing simple alert controls`() {
        val app: Application = RuntimeEnvironment.getApplication()
        val controls = AlertPreferencesStore(app)
        val customization = AppSettingsStore(app)
        customization.set("alert_arrival", "false")
        customization.set("alert_exit", "false")
        customization.set("alert_leads", "300,120")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(AlertPreferences(false, false, 120), controls.state.value)
        controls.close()
        customization.close()
    }
}
