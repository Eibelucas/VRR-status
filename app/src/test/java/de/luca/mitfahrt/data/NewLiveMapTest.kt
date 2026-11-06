package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test

class NewLiveMapTest {
    @Test fun `official map and its routes stay inside the map view`() {
        assertTrue(NewLiveMap.isInternal(NewLiveMap.URL))
        assertTrue(NewLiveMap.isInternal("https://livebus.new.de/maps/SLNP?line=006"))
        assertTrue(NewLiveMap.isInternal("https://livebus.new.de:443/"))
    }

    @Test fun `lookalike origins local files and insecure map navigation are blocked`() {
        listOf("https://livebus.new.de.evil.example/", "https://livebus.new.de@evil.example/",
            "https://someone@livebus.new.de/", "http://livebus.new.de/", "https://livebus.new.de:8443/",
            "file:///data/data/de.luca.mitfahrt/", "javascript:alert(1)", "intent://new.de", "not a url")
            .forEach { assertFalse(it, NewLiveMap.isInternal(it)) }
    }

    @Test fun `external links cannot invoke scripts intents or local content`() {
        assertTrue(NewLiveMap.isExternalLink("https://www.new.de/oepnv/fahrplan"))
        listOf("file:///data/", "content://de.luca.mitfahrt.reports/", "javascript:alert(1)",
            "intent://anything", "https://user:password@example.org/", "https:///missing-host", "not a url")
            .forEach { assertFalse(it, NewLiveMap.isExternalLink(it)) }
    }
}
