package de.luca.mitfahrt.shared

import java.io.*
import java.time.Instant

data class WatchRide(
    val sentAt: Instant,
    val active: Boolean = false,
    val running: Boolean = false,
    val fetchedAt: Instant? = null,
    val line: String = "",
    val destination: String = "",
    val nextStop: String = "",
    val boarded: Boolean = false,
    val boardingAt: Instant? = null,
    val boardingIsArrival: Boolean = false,
    val terminalAt: Instant? = null,
    val source: String = "",
    val delay: String = "",
    val demo: Boolean = false,
    val cancelled: Boolean = false,
    val refreshFailed: Boolean = false
) {
    fun stale(now: Instant): Boolean = !running || refreshFailed || fetchedAt == null ||
        now.epochSecond - fetchedAt.epochSecond !in -60..120 ||
        now.epochSecond - sentAt.epochSecond !in -60..120
}

object WatchProtocol {
    const val PATH = "/mitfahrt/ride/v1"
    const val REQUEST = "/mitfahrt/request/v1"
    const val MAX_BYTES = 8192

    fun encode(ride: WatchRide): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeLong(ride.sentAt.toEpochMilli())
            out.writeBoolean(ride.active); out.writeBoolean(ride.running)
            out.instant(ride.fetchedAt)
            listOf(ride.line, ride.destination, ride.nextStop).forEach { out.writeUTF(it.take(256)) }
            out.writeBoolean(ride.boarded); out.instant(ride.boardingAt)
            out.writeBoolean(ride.boardingIsArrival); out.instant(ride.terminalAt)
            out.writeUTF(ride.source.take(256)); out.writeUTF(ride.delay.take(256))
            out.writeBoolean(ride.demo); out.writeBoolean(ride.cancelled); out.writeBoolean(ride.refreshFailed)
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): WatchRide {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1) { "Unsupported watch protocol" }
            WatchRide(Instant.ofEpochMilli(input.readLong()), input.readBoolean(), input.readBoolean(), input.instant(),
                input.readUTF(), input.readUTF(), input.readUTF(), input.readBoolean(), input.instant(), input.readBoolean(),
                input.instant(), input.readUTF(), input.readUTF(), input.readBoolean(), input.readBoolean(), input.readBoolean())
                .also { require(input.available() == 0) }
        }
    }

    private fun DataOutputStream.instant(value: Instant?) { writeBoolean(value != null); value?.let { writeLong(it.toEpochMilli()) } }
    private fun DataInputStream.instant(): Instant? = if (readBoolean()) Instant.ofEpochMilli(readLong()) else null
}
