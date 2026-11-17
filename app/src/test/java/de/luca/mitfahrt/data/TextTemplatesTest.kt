package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test

class TextTemplatesTest {
    private val values = mapOf("linie" to "017", "ziel" to "Hbf", "minuten" to "", "verspaetung" to "+2 Min", "status" to "Bus kommt in 6:00")

    @Test fun `placeholders are replaced`() {
        assertEquals("017 → Hbf", TextTemplates.render("{linie} → {ziel}", values))
    }

    @Test fun `a part with a missing value is left out instead of showing a gap`() {
        assertEquals("017 · +2 Min", TextTemplates.render("{linie} · {minuten} Min · {verspaetung}", values))
        assertEquals("", TextTemplates.render("{minuten} Min", values))
    }

    @Test fun `unknown names are reported and never rendered as a value`() {
        assertEquals(listOf("linje"), TextTemplates.unknown("{linje} {ziel}"))
        assertEquals("Hbf", TextTemplates.render("{linje} · {ziel}", values))
        assertTrue(TextTemplates.unknown("{linie} · {ziel}").isEmpty())
    }

    @Test fun `own texts apply only when switched on and written`() {
        val off = AppSettings(mapOf("tpl_nowbar_chip" to "{linie}!"))
        assertNull(TextTemplates.custom(off, "tpl_nowbar_chip", values))
        val on = AppSettings(mapOf("templates_enabled" to "true", "tpl_nowbar_chip" to "{linie}!", "tpl_nowbar_title_wait" to "{minuten}"))
        assertEquals("017!", TextTemplates.custom(on, "tpl_nowbar_chip", values))
        assertNull("empty template keeps the built-in text", TextTemplates.custom(on, "tpl_nowbar_text_wait", values))
        assertNull("nothing left after dropping parts keeps the built-in text", TextTemplates.custom(on, "tpl_nowbar_title_wait", values))
    }

    @Test fun `length limits and line breaks are enforced`() {
        assertEquals("a b", TextTemplates.clean("a\nb", 10))
        assertEquals(24, TextTemplates.clean("x".repeat(80), 24).length)
        val on = AppSettings(mapOf("templates_enabled" to "true", "tpl_nowbar_chip" to "{status} {status} {status}"))
        assertTrue(TextTemplates.custom(on, "tpl_nowbar_chip", values)!!.length <= 24)
    }

    @Test fun `every default and every sample is valid`() {
        TextTemplates.slots.forEach { slot ->
            assertTrue(slot.key, TextTemplates.unknown(slot.default).isEmpty())
            assertTrue(slot.key, TextTemplates.render(slot.default, TextTemplates.sample).isNotBlank())
            assertNotNull(slot.key, SettingsCatalog.definitions.firstOrNull { it.key == slot.key })
        }
    }

    @Test fun `values of a synthetic ride cover every variable and stay empty where nothing is known`() {
        val t0 = RouteFixtures.T0
        val waiting = RouteFixtures.ride(boarded = false)
        val now = t0.plusSeconds(30)
        val plain = TextTemplates.values(TrackingState(ride = waiting, running = true), null, now)
        assertEquals(TextTemplates.variables.map { it.name }.toSet(), plain.keys)
        assertEquals("006", plain["linie"]); assertEquals("Halt 5", plain["ziel"]); assertEquals("Warten", plain["phase"])
        assertEquals("no live forecast: no live minutes", "", plain["minuten"])
        assertEquals("", plain["verspaetung"]); assertEquals("", plain["berechnet"]); assertEquals("", plain["umstieg_linie"]); assertEquals("", plain["fahrzeug"])
        assertEquals("08:02", plain["abfahrt_plan"])

        val live = RouteFixtures.ride(boarded = false, delaySeconds = 120L, fetchedAt = now)
        val delayed = TextTemplates.values(TrackingState(ride = live, running = true), null, now)
        assertEquals("+2 Min", delayed["verspaetung"]); assertEquals("2", delayed["verspaetung_minuten"])
        assertEquals("08:04", delayed["abfahrt"]); assertEquals("4", delayed["minuten"]); assertEquals("Live", delayed["quelle"])

        val riding = live.board("auto", t0.plusSeconds(240))
        val bus = RouteFixtures.bus("7314", 1000.0, 0.0, now.minusSeconds(5))
        val state = TrackingState(ride = riding, running = true, routeShape = RouteFixtures.shape(riding),
            liveBus = LiveBusState(journeyKey = riding.departure.key, vehicle = bus, selectedVehicleId = "7314", routeOffset = 1000.0))
        val onBoard = TextTemplates.values(state, null, now)
        assertEquals("Im Bus", onBoard["phase"]); assertEquals("7314", onBoard["fahrzeug"]); assertEquals("08:04", onBoard["im_bus_seit"])
        assertEquals("6", onBoard["berechnet"]); assertTrue(onBoard["ankunft_berechnet"]!!.isNotEmpty())
        assertEquals("08:12", onBoard["ankunft"]); assertEquals("08:10", onBoard["ankunft_plan"])
        assertTrue(onBoard["halt"]!!.startsWith("Halt")); assertTrue(onBoard["halte"]!!.toInt() >= 1)
        assertEquals("", onBoard["bus_entfernung"])
        assertTrue(TextTemplates.values(TrackingState(), null, now).isEmpty())
    }
    @Test fun `old forecasts never fill the expected time placeholders`() {
        val t0 = RouteFixtures.T0
        val ride = RouteFixtures.ride(boarded = true, delaySeconds = 120, fetchedAt = t0)
        val values = TextTemplates.values(TrackingState(ride = ride, running = true), null, t0.plusSeconds(121))
        assertEquals("", values["abfahrt"])
        assertEquals("", values["ankunft"])
        assertEquals("", values["minuten"])
        assertTrue(values["ankunft_plan"]!!.isNotBlank())
    }

    @Test fun `disabled position calculations also stay empty in custom texts`() {
        val now = RouteFixtures.T0.plusSeconds(300)
        val ride = RouteFixtures.ride(boarded = true)
        val bus = RouteFixtures.bus("v7", 1000.0, 0.0, now)
        val state = TrackingState(ride = ride, running = true, routeShape = RouteFixtures.shape(ride),
            liveBus = LiveBusState(journeyKey = ride.departure.key, vehicle = bus, selectedVehicleId = bus.id))
        val values = TextTemplates.values(state, null, now, travelEnabled = false)
        assertEquals("", values["berechnet"])
        assertEquals("", values["ankunft_berechnet"])
        assertEquals("", values["halt_berechnet"])
    }

    @Test fun `custom calculated notification text is identified for the source label`() {
        val settings = AppSettings(mapOf("templates_enabled" to "true", "tpl_nowbar_chip" to "{berechnet} Min"))
        assertTrue(TextTemplates.hasCalculation(settings, listOf("tpl_nowbar_chip"), mapOf("berechnet" to "4")))
        assertFalse(TextTemplates.hasCalculation(settings, listOf("tpl_nowbar_chip"), mapOf("berechnet" to "")))
        assertFalse(TextTemplates.hasCalculation(AppSettings(), listOf("tpl_nowbar_chip"), mapOf("berechnet" to "4")))
    }

}
