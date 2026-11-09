package de.luca.mitfahrt.widgets

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.widget.Chronometer
import android.widget.TextView
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.Departure
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
class WidgetRendererTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun clear() { app.getSharedPreferences("app_customization", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `small widget keeps forecast clock and independently advancing source age visible`() {
        val now = Instant.now()
        val source = now.minusSeconds(90)
        val departure = Departure("line", "015", "Hbf", "de:05116:1", "Hehn", "trip", now.plusSeconds(300),
            now.plusSeconds(360), "1", observedAt = source)
        val config = WidgetConfig(WidgetKind.STOP, "de:05116:1", "Hehn")
        val model = WidgetPresenter.present(config, WidgetSnapshot(now, listOf(departure)), now)
        val options = Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 70)
        }
        val view = WidgetRenderer.build(app, 42, config, model, options).apply(app, null)
        val age = view.findViewById<Chronometer>(R.id.mitfahrt_widget_row_label)
        assertNotNull(age)
        assertEquals(android.view.View.VISIBLE, age.visibility)
        assertTrue(age.format.contains("Prognose")); assertTrue(age.format.contains("%s"))
        assertTrue(SystemClock.elapsedRealtime() - age.base in 90_000..92_000)
        val clock = view.findViewById<TextView>(R.id.mitfahrt_widget_row_time).text.toString()
        assertEquals(model.rows.single().time, clock)
        assertFalse(clock.contains("min")); assertFalse(clock.contains("jetzt"))
    }
}
