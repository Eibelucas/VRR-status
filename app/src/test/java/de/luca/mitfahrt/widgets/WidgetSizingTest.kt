package de.luca.mitfahrt.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSizingTest {
    @Test fun `one cell high widget keeps a single departure without header`() {
        val spec = WidgetSizing.spec(250, 70, InfoLevel.DETAILED, WidgetKind.STOP)
        assertEquals(1, spec.rows)
        assertFalse(spec.showHeader)
        assertFalse(spec.showDetail)
    }

    @Test fun `information level caps the rows of a large widget`() {
        assertEquals(2, WidgetSizing.spec(300, 300, InfoLevel.MINIMAL, WidgetKind.STOP).rows)
        assertEquals(3, WidgetSizing.spec(300, 300, InfoLevel.NORMAL, WidgetKind.STOP).rows)
        val detailed = WidgetSizing.spec(300, 300, InfoLevel.DETAILED, WidgetKind.STOP)
        assertEquals(4, detailed.rows)
        assertTrue(detailed.showDetail)
    }

    @Test fun `size limits rows before they would be cut`() {
        val spec = WidgetSizing.spec(250, 110, InfoLevel.DETAILED, WidgetKind.ROUTE)
        assertTrue(spec.showHeader)
        assertEquals(1, spec.rows)
        assertEquals(2, WidgetSizing.spec(250, 150, InfoLevel.NORMAL, WidgetKind.ROUTE).rows)
    }

    @Test fun `map button only for active ride with enough width`() {
        assertTrue(WidgetSizing.spec(250, 180, InfoLevel.NORMAL, WidgetKind.ACTIVE).showMap)
        assertFalse(WidgetSizing.spec(120, 180, InfoLevel.NORMAL, WidgetKind.ACTIVE).showMap)
        assertFalse(WidgetSizing.spec(250, 180, InfoLevel.NORMAL, WidgetKind.STOP).showMap)
    }

    @Test fun `explicit widget count can show five rows when the launcher provides enough height`() {
        assertEquals(5, WidgetSizing.spec(300, 420, InfoLevel.NORMAL, WidgetKind.STOP, rowLimit = 5).rows)
        assertEquals(1, WidgetSizing.spec(300, 420, InfoLevel.DETAILED, WidgetKind.STOP, rowLimit = 1).rows)
    }
}
