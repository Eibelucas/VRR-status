package de.luca.mitfahrt.shared

import java.time.Instant

object ArrivalAlert {
    const val PATH = "/mitfahrt/arrival-alert/v1"
    fun due(ride: WatchRide, now: Instant): Boolean = ride.active && !ride.boarded && !ride.demo && !ride.cancelled &&
        !ride.stale(now) && ride.boardingAt?.let { it.epochSecond - now.epochSecond in 1..60 } == true
    fun encode(id: String, now: Instant, boardingAt: Instant = now.plusSeconds(60)): ByteArray {
        require(id.isNotBlank() && id.length <= 1024 && !id.contains('\n'))
        return "$id\n${now.toEpochMilli()}\n${boardingAt.toEpochMilli()}".toByteArray(Charsets.UTF_8)
    }
    fun recentId(bytes: ByteArray, now: Instant): String? = runCatching {
        require(bytes.size <= 2048)
        val parts = bytes.toString(Charsets.UTF_8).split('\n')
        require(parts.size == 3 && parts[0].isNotBlank() && parts[0].length <= 1024)
        require(now.toEpochMilli() - parts[1].toLong() in -5000..30000)
        require(now.toEpochMilli() < parts[2].toLong())
        parts[0]
    }.getOrNull()
}
