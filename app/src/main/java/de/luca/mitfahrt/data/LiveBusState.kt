package de.luca.mitfahrt.data

import java.time.Instant

/** Volatile vehicle telemetry is kept separate from the saved VRR journey and school evidence. */
data class LiveBusState(
    val journeyKey: String? = null,
    val revision: Long = 0L,
    val candidates: List<NewVehicle> = emptyList(),
    val vehicle: NewVehicle? = null,
    val selectedVehicleId: String? = null,
    /** Only an independent NEW schedule match; never set by movement-based detection. */
    val confirmed: Boolean = false,
    val fetchedAt: Instant? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Local automatic detection; a result here is a likely vehicle, not an operator assignment. */
    val auto: AutoMatchState = AutoMatchState(),
    /** Position of [vehicle] on the verified line, only when it is close enough and heading the same way. */
    val routePoint: GeoPoint? = null,
    val routeOffset: Double? = null,
    /** The vehicle repeatedly reports positions away from the known line (possible diversion). */
    val offRoute: Boolean = false
)
