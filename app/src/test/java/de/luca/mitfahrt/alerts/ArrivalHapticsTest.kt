package de.luca.mitfahrt.alerts

import android.app.Application
import android.os.Vibrator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ArrivalHapticsTest {
    @Test fun `duplicate events stay silent across new callers but another trip vibrates`() {
        val app: Application = RuntimeEnvironment.getApplication()
        val vibrator = app.getSystemService(Vibrator::class.java)
        ArrivalHaptics.pulse(app, "one")
        assertTrue(shadowOf(vibrator).isVibrating)
        vibrator.cancel()
        ArrivalHaptics.pulse(app, "one")
        assertFalse(shadowOf(vibrator).isVibrating)
        ArrivalHaptics.pulse(app, "two")
        assertTrue(shadowOf(vibrator).isVibrating)
    }
}
