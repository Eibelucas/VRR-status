package de.luca.mitfahrt.data

import java.time.Instant

/** Volatile vehicle telemetry is kept separate from the saved VRR journey and school evidence. */
data class LiveBusState(
    val journeyKey: String? = null,
    val revision: Long = 0L,
    val candidates: List<NewVehicle> = emptyList(),
    val vehicle: NewVehicle? = null,
    val selectedVehicleId: String? = null,
    val confirmed: Boolean = false,
    val fetchedAt: Instant? = null,
    val loading: Boolean = false,
    val error: String? = null
)
