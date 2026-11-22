package de.luca.mitfahrt.data

import de.luca.mitfahrt.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/**
 * OpenStreetMap Photon geocoder for businesses, POIs and exact house addresses (Google-Maps-style search).
 * Runs alongside VRR StopFinder and produces coordinate-backed [TripPlace] entries that VRR EFA routes door-to-door.
 */
object PlaceGeocoder {
    /** Default locality bias (Mönchengladbach center) when the user's own position is unavailable. */
    val DEFAULT_BIAS = GeoPoint(51.1950, 6.4400)
    private const val ENDPOINT = "https://photon.komoot.io/api/"

    suspend fun search(query: String, bias: GeoPoint? = null): List<TripPlace> {
        val trimmed = query.trim().take(100)
        if (trimmed.length < 2) return emptyList()
        val anchor = bias?.takeIf(Geo::valid) ?: DEFAULT_BIAS
        val url = buildUrl(trimmed, anchor)
        val raw = fetchJson(url) ?: return emptyList()
        return parse(raw, anchor)
    }

    internal fun buildUrl(query: String, anchor: GeoPoint): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return String.format(
            Locale.ROOT,
            "%s?q=%s&lat=%.5f&lon=%.5f&lang=de&limit=12&location_bias_scale=0.35",
            ENDPOINT, encoded, anchor.lat, anchor.lon
        )
    }

    internal fun parse(root: JSONObject, anchor: GeoPoint = DEFAULT_BIAS): List<TripPlace> {
        val features = root.optJSONArray("features") ?: return emptyList()
        val results = mutableListOf<TripPlace>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            val point = GeoPoint(lat, lon).takeIf(Geo::valid) ?: continue

            val osmKey = props.optString("osm_key").lowercase(Locale.ROOT)
            val osmValue = props.optString("osm_value").lowercase(Locale.ROOT)
            val type = props.optString("type").lowercase(Locale.ROOT)

            // Skip transit stops/platforms so official VRR StopFinder stops with exact stop IDs stay authoritative.
            if (osmKey == "public_transport" || osmValue in TRANSIT_VALUES) continue
            // Skip unnamed service roads/footways that clutter business searches.
            if (osmKey == "highway" && osmValue in NOISY_HIGHWAYS) continue

            val rawName = props.optString("name").trim()
            val street = props.optString("street").trim()
            val house = props.optString("housenumber").trim()
            val postcode = props.optString("postcode").trim()
            val city = props.optString("city").trim()
                .ifBlank { props.optString("town").trim() }
                .ifBlank { props.optString("village").trim() }
                .ifBlank { props.optString("county").trim() }
            val district = props.optString("district").trim()

            val streetLine = listOf(street, house).filter(String::isNotBlank).joinToString(" ")
            val cityLine = listOf(postcode, city).filter(String::isNotBlank).joinToString(" ")

            val isHouse = type == "house" || (street.isNotBlank() && house.isNotBlank() && rawName.isBlank())
            val isStreet = type == "street" || (osmKey == "highway" && rawName.isNotBlank() && house.isBlank())
            val isLocality = type in setOf("city", "locality", "district", "county")

            val kind = when {
                isHouse -> PlaceKind.ADDRESS
                isStreet -> PlaceKind.STREET
                isLocality -> PlaceKind.LOCALITY
                else -> PlaceKind.POI
            }

            val title = when {
                rawName.isNotBlank() && isHouse && streetLine.isNotBlank() && !rawName.equals(streetLine, true) ->
                    "$rawName ($streetLine)"
                rawName.isNotBlank() -> rawName
                streetLine.isNotBlank() -> streetLine
                else -> continue
            }

            val subtitle = when (kind) {
                PlaceKind.ADDRESS -> listOfNotNull(
                    cityLine.ifBlank { null },
                    district.takeIf { it.isNotBlank() && !it.equals(city, true) }
                ).joinToString(" · ")
                PlaceKind.STREET -> listOfNotNull(
                    district.takeIf { it.isNotBlank() && !it.equals(city, true) },
                    cityLine.ifBlank { null }
                ).joinToString(" · ")
                PlaceKind.LOCALITY -> listOfNotNull(
                    city.takeIf { it.isNotBlank() && !it.equals(title, true) },
                    props.optString("state").trim().ifBlank { null }
                ).joinToString(" · ")
                else -> listOfNotNull(
                    streetLine.takeIf { it.isNotBlank() && !it.equals(title, true) },
                    city.takeIf { it.isNotBlank() && !it.equals(title, true) }
                ).joinToString(", ")
            }

            val category = if (kind == PlaceKind.POI) germanCategory(osmKey, osmValue) else ""

            // Keep results within reasonable VRR / NRW commuting reach (~120 km) unless explicitly named with a far city.
            val distanceKm = anchor.distanceTo(point) / 1000.0
            if (distanceKm > 250.0) continue

            results += TripPlace(
                kind = kind,
                id = "",
                name = title.take(120),
                locality = subtitle.take(120),
                point = point,
                category = category
            )
        }
        return results.distinctBy { it.key }
    }

    /**
     * Merges VRR EFA stops/places with Photon businesses/addresses into one Google-Maps-like list:
     * deduplicates near-identical hits and ranks by text match, local proximity and usefulness.
     */
    fun merge(
        query: String,
        vrr: List<TripPlace>,
        geocoded: List<TripPlace>,
        bias: GeoPoint? = null,
        limit: Int = 20
    ): List<TripPlace> {
        val anchor = bias?.takeIf(Geo::valid) ?: DEFAULT_BIAS
        val needle = normalize(query)
        val tokens = needle.split(' ').filter { it.length >= 2 }
        val hasNumber = query.any(Char::isDigit)

        val combined = mutableListOf<TripPlace>()
        combined += vrr
        for (geo in geocoded) {
            val geoNorm = normalize(geo.name)
            val duplicate = combined.any { existing ->
                val sameName = normalize(existing.name) == geoNorm
                val close = existing.point != null && geo.point != null &&
                    existing.point.distanceTo(geo.point) < 90.0
                (sameName && (close || existing.locality.equals(geo.locality, true))) ||
                    (close && existing.kind == geo.kind && geoNorm.contains(normalize(existing.name).take(6)))
            }
            if (!duplicate) combined += geo
        }

        fun score(place: TripPlace): Double {
            var s = 0.0
            val nameNorm = normalize(place.name)
            val locNorm = normalize(place.locality)
            val fullNorm = "$nameNorm $locNorm"

            // Text match quality
            when {
                nameNorm == needle -> s += 110.0
                nameNorm.startsWith(needle) -> s += 85.0
                needle in nameNorm -> s += 62.0
                tokens.isNotEmpty() && tokens.all { it in fullNorm } -> s += 55.0
                tokens.isNotEmpty() -> s += 25.0 * tokens.count { it in fullNorm } / tokens.size
            }

            // Local proximity boost (Mönchengladbach / user location strongly preferred over far-away cities)
            val distKm = place.point?.takeIf(Geo::valid)?.let { anchor.distanceTo(it) / 1000.0 }
            if (distKm != null) {
                s += when {
                    distKm <= 2.0 -> 55.0
                    distKm <= 8.0 -> 45.0
                    distKm <= 18.0 -> 32.0
                    distKm <= 35.0 -> 14.0
                    distKm <= 70.0 -> 0.0
                    else -> -25.0
                } - min(distKm, 20.0) * 0.4
            } else if ("monchengladbach" in locNorm || "mg" in locNorm) {
                s += 28.0
            }

            // Kind priority: house numbers boost ADDRESS; transit keywords boost STOP; businesses get clean boost.
            s += when (place.kind) {
                PlaceKind.STOP -> if (hasNumber) 10.0 else 22.0
                PlaceKind.ADDRESS -> if (hasNumber) 32.0 else 12.0
                PlaceKind.POI -> if (place.category.isNotBlank()) 24.0 else 16.0
                PlaceKind.STREET -> 10.0
                PlaceKind.LOCALITY -> 4.0
                else -> 0.0
            }
            return s
        }

        return combined
            .distinctBy { it.key }
            .sortedByDescending(::score)
            .take(limit)
    }

    private fun germanCategory(key: String, value: String): String = when {
        value == "supermarket" -> "Supermarkt"
        value == "mall" || value == "department_store" -> "Einkaufszentrum"
        value == "bakery" -> "Bäckerei"
        value == "chemist" -> "Drogerie"
        value == "pharmacy" -> "Apotheke"
        value == "hospital" || value == "clinic" -> "Krankenhaus"
        value == "doctors" || value == "dentist" -> "Arztpraxis"
        value == "restaurant" -> "Restaurant"
        value == "fast_food" -> "Fast Food"
        value == "cafe" -> "Café"
        value == "bar" || value == "pub" -> "Bar"
        value == "school" -> "Schule"
        value == "university" || value == "college" -> "Hochschule"
        value == "kindergarten" -> "Kita"
        value == "bank" || value == "atm" -> "Bank"
        value == "cinema" -> "Kino"
        value == "library" -> "Bibliothek"
        value == "fuel" -> "Tankstelle"
        value == "parking" -> "Parkplatz"
        value == "townhall" -> "Rathaus"
        value == "post_office" -> "Post"
        value == "police" -> "Polizei"
        value == "fitness_centre" || value == "sports_centre" -> "Sport & Fitness"
        value == "stadium" -> "Stadion"
        value == "park" -> "Park"
        value == "hotel" || value == "hostel" -> "Hotel"
        value == "museum" -> "Museum"
        value == "hairdresser" -> "Friseur"
        value == "clothes" || value == "shoes" -> "Mode"
        value == "electronics" -> "Elektronik"
        value == "doityourself" || value == "hardware" -> "Baumarkt"
        value == "furniture" -> "Möbelhaus"
        key == "shop" -> "Geschäft"
        key == "office" -> "Büro & Unternehmen"
        key == "industrial" || key == "craft" -> "Betrieb"
        key == "leisure" -> "Freizeit"
        key == "tourism" -> "Sehenswürdigkeit"
        else -> ""
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace("ß", "ss")
        .replace("straße", "strasse")
        .replace("str.", "strasse")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private suspend fun fetchJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_500
            readTimeout = 3_500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mitfahrt-Android/${BuildConfig.VERSION_NAME} (https://github.com/Eibelucas/VRR-status)")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > 512 * 1024) return@withContext null
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            JSONObject(bytes)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private val TRANSIT_VALUES = setOf("bus_stop", "platform", "station", "halt", "tram_stop", "stop_position", "bus_station")
    private val NOISY_HIGHWAYS = setOf("service", "footway", "path", "track", "steps", "cycleway", "unclassified")
}
