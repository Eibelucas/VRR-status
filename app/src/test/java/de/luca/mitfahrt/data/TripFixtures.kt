package de.luca.mitfahrt.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Synthetic EFA rapidJSON trip answers, built after the documented field layout
 * (journeys[].legs[].origin/destination/transportation/stopSequence/coords/footPathInfo/infos).
 * They are no recording of a real VRR answer.
 */
object TripFixtures {
    /** 10.11.2026 08:00 in Mönchengladbach (CET). */
    val T0: Instant = Instant.parse("2026-11-10T07:00:00Z")
    fun at(minutes: Long): String = T0.plusSeconds(minutes * 60).toString()

    fun stop(
        id: String, name: String, lat: Double, lon: Double,
        planDep: Long? = null, estDep: Long? = null, planArr: Long? = null, estArr: Long? = null,
        platform: String = "", status: List<String> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("id", id); put("name", "$name, Gleis/Steig"); put("type", "platform")
        put("disassembledName", platform)
        put("coord", JSONArray(listOf(lat, lon)))
        put("parent", JSONObject().put("id", id.split(':').take(3).joinToString(":")).put("name", name).put("type", "stop"))
        if (platform.isNotBlank()) put("properties", JSONObject().put("platform", platform.filter(Char::isDigit)).put("platformName", platform))
        planDep?.let { put("departureTimePlanned", at(it)) }
        estDep?.let { put("departureTimeEstimated", at(it)) }
        planArr?.let { put("arrivalTimePlanned", at(it)) }
        estArr?.let { put("arrivalTimeEstimated", at(it)) }
        if (status.isNotEmpty()) put("realtimeStatus", JSONArray(status))
    }

    fun place(id: String, name: String, lat: Double, lon: Double, planDep: Long? = null, planArr: Long? = null) = JSONObject().apply {
        put("id", id); put("name", name); put("type", "address"); put("coord", JSONArray(listOf(lat, lon)))
        planDep?.let { put("departureTimePlanned", at(it)) }
        planArr?.let { put("arrivalTimePlanned", at(it)) }
    }

    fun transit(
        id: String, number: String, operator: String, productClass: Int, productName: String, direction: String,
        tripCode: String, origin: JSONObject, destination: JSONObject, between: List<JSONObject> = emptyList(),
        coords: List<Pair<Double, Double>>? = null, infos: List<String> = emptyList(), status: List<String> = emptyList(),
        realtime: Boolean = true
    ): JSONObject = JSONObject().apply {
        put("duration", 600)
        put("isRealtimeControlled", realtime)
        put("origin", origin); put("destination", destination)
        put("transportation", JSONObject().apply {
            put("id", id); put("name", "$productName $number"); put("disassembledName", number); put("number", number)
            put("product", JSONObject().put("class", productClass).put("name", productName))
            put("operator", JSONObject().put("code", operator))
            put("destination", JSONObject().put("name", direction))
            put("properties", JSONObject().put("tripCode", tripCode))
        })
        put("stopSequence", JSONArray(listOf(origin) + between + listOf(destination)))
        coords?.let { list -> put("coords", JSONArray(list.map { JSONArray(listOf(it.first, it.second)) })) }
        if (infos.isNotEmpty()) put("infos", JSONArray(infos.map { JSONObject().put("priority", "normal").put("subtitle", it) }))
        if (status.isNotEmpty()) put("realtimeStatus", JSONArray(status))
    }

    fun walk(origin: JSONObject, destination: JSONObject, seconds: Int, productClass: Int = 100, elements: List<String> = emptyList()) =
        JSONObject().apply {
            put("duration", seconds); put("distance", seconds)
            put("origin", origin); put("destination", destination)
            put("transportation", JSONObject().put("product", JSONObject().put("class", productClass).put("name", "Fußweg")))
            if (elements.isNotEmpty()) put("footPathInfo", JSONArray().put(JSONObject().put("position", "IDEST").put("duration", seconds)
                .put("footPathElem", JSONArray(elements.map { JSONObject().put("type", it) }))))
        }

    fun journey(vararg legs: JSONObject, interchanges: Int? = null) = JSONObject().apply {
        interchanges?.let { put("interchanges", it) }
        put("legs", JSONArray(legs.toList()))
    }

    fun root(vararg journeys: JSONObject) = JSONObject().put("journeys", JSONArray(journeys.toList()))

    /**
     * Address → walk → NEW bus 017 → walk with stairs → regional train → walk → address.
     * Bus arrives 08:16 (plan 08:13), 3 min walk, train leaves 08:22 (plan 08:20).
     */
    fun busAndTrain(trainEstDep: Long? = 22, busEstArr: Long? = 16, trainStatus: List<String> = emptyList()): JSONObject {
        val home = place("streetID:1:Hauptstr", "Hauptstraße 10", 51.190, 6.440, planDep = -4)
        val boarding = stop("de:05116:7300:1:2", "MG Bismarckplatz", 51.192, 6.442, planDep = 0, estDep = 3, platform = "Bstg. 2")
        val middle = stop("de:05116:7400:1:1", "MG Marienplatz", 51.170, 6.430, planArr = 7, planDep = 7, estArr = 10, estDep = 10)
        val busExit = stop("de:05116:7500:1:3", "Rheydt Hbf", 51.160, 6.440, planArr = 13, estArr = busEstArr, platform = "Bstg. 3")
        val trainBoard = stop("de:05116:7500:2:2", "Rheydt Hbf", 51.161, 6.441, planDep = 20, estDep = trainEstDep, platform = "Gleis 2", status = trainStatus)
        val trainExit = stop("de:05315:11201:1:4", "Köln Hbf", 50.943, 6.958, planArr = 75, estArr = trainEstDep?.let { it + 55 }, platform = "Gleis 4")
        val target = place("streetID:2:Domplatte", "Domkloster 4", 50.941, 6.957, planArr = 80)
        return journey(
            walk(home, boarding, 240),
            transit("new:80017: :H:m26", "017", "NEW", 5, "Bus", "Rheydt Hbf", "4711", boarding, busExit, listOf(middle),
                coords = listOf(51.192 to 6.442, 51.18 to 6.435, 51.170 to 6.430, 51.160 to 6.440), infos = listOf("Umleitung Marienplatz")),
            walk(busExit, trainBoard, 180, productClass = 99, elements = listOf("STAIRS", "LEVEL", "ELEVATOR")),
            transit("ddb:90E08:R:j26", "RE8", "DBNW", 13, "Regionalzug", "Köln/Bonn Flughafen", "10123", trainBoard, trainExit,
                status = trainStatus, realtime = trainEstDep != null),
            walk(trainExit, target, 300),
            interchanges = 1
        )
    }
}
